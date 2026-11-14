package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEventDeserializer
import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import okhttp3.*
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE (Server-Sent Events) listener for OpenCode server.
 */
class SseEventListener(
    private val baseUrl: String,
    private val projectPath: String,
    private val onEvent: (OpenCodeEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val username: String? = null,
    private val password: String? = null
) {
    private val logger = Logger.getInstance(SseEventListener::class.java)

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val MAX_EVENT_LINE_BYTES = 8L * 1024 * 1024
    }

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (username != null && password != null) {
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    private val gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .create()

    private var call: Call? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectAttemptCount = 0

    /**
     * Bumped on every connect/disconnect. A callback captures the value it was started with and
     * bails out when it no longer matches, so a cancelled call can neither touch state nor
     * schedule another reconnect after a newer listener generation took over.
     */
    @Volatile private var generation = 0

    fun connect() {
        if (isConnected.get()) return
        shouldReconnect.set(true)
        reconnectAttemptCount = 0
        reconnectTask?.cancel(false)
        reconnectTask = null
        doConnect()
    }

    private fun doConnect() {
        val connectGeneration = ++generation
        reconnectAttemptCount++
        val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
        val url = "$baseUrl/event?directory=$encodedPath"

        logger.info("[SSE] Connecting to $url (attempt #$reconnectAttemptCount)...")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val newCall = client.newCall(request)
        call = newCall
        newCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (connectGeneration != generation) return
                isConnected.set(false)
                logger.warn("[SSE] Connection failure: ${e.message} (attempt #$reconnectAttemptCount)")
                onError(e)
                onDisconnected()
                scheduleReconnect(connectGeneration)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (connectGeneration != generation) return
                    if (!response.isSuccessful) {
                        isConnected.set(false)
                        val body = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                        logger.error("[SSE] Handshake failed: HTTP ${response.code} (attempt #$reconnectAttemptCount). Response: $body")
                        onError(IOException("SSE failed: ${response.code}"))
                        onDisconnected()
                        scheduleReconnect(connectGeneration)
                        return
                    }

                    isConnected.set(true)
                    reconnectAttemptCount = 0
                    onConnected()
                    logger.info("[SSE] Connection established")

                    val source = response.body?.source() ?: run {
                        isConnected.set(false)
                        onDisconnected()
                        scheduleReconnect(connectGeneration)
                        return
                    }

                    var oversizedLine = false
                    try {
                        while (!source.exhausted() && !call.isCanceled() && connectGeneration == generation) {
                            if (!hasCompleteLine(source)) {
                                if (!source.exhausted()) {
                                    oversizedLine = true
                                    logger.warn("[SSE] Dropping event line larger than ${MAX_EVENT_LINE_BYTES / (1024 * 1024)}MB; reconnecting instead of buffering it")
                                }
                                break
                            }
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data:")) {
                                val json = line.substring(5).trim()
                                if (json.isNotEmpty()) parseAndDispatchEvent(json)
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) logger.warn("[SSE] Stream interrupted: ${e.message}")
                    } finally {
                        isConnected.set(false)
                        onDisconnected()
                        if (connectGeneration == generation && (oversizedLine || (shouldReconnect.get() && !call.isCanceled()))) {
                            logger.info("[SSE] Connection lost. Scheduling reconnect.")
                            scheduleReconnect(connectGeneration)
                        }
                    }
                } finally {
                    response.body?.close()
                }
            }
        })
    }

    /**
     * True when the buffer already holds a newline within [MAX_EVENT_LINE_BYTES].
     * A false result means either the stream ended or the pending line is too long, so the caller
     * never materialises an unbounded string (a single session.diff event can be tens of MB).
     */
    private fun hasCompleteLine(source: BufferedSource): Boolean =
        source.indexOf('\n'.code.toByte(), 0L, MAX_EVENT_LINE_BYTES + 1) != -1L

    private fun parseAndDispatchEvent(json: String) {
        try {
            val event = gson.fromJson(json, OpenCodeEvent::class.java)
            if (event != null) {
                logger.debug("[SSE] Event ${event.type} (${json.length} chars)")
                onEvent(event)
            }
        } catch (e: Exception) {
            logger.debug("[SSE] Parse error for ${json.length}-char payload: ${e.message}")
        }
    }

    private fun scheduleReconnect(connectGeneration: Int) {
        if (!shouldReconnect.get() || connectGeneration != generation) return
        reconnectTask?.cancel(false)
        val delayMs = reconnectDelayMs(reconnectAttemptCount)
        logger.info("[SSE] Reconnect in ${delayMs}ms (attempt #${reconnectAttemptCount + 1})")
        reconnectTask = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (shouldReconnect.get() && connectGeneration == generation) doConnect()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        var delay = INITIAL_RECONNECT_DELAY_MS
        var remaining = (attempt - 1).coerceAtLeast(0)
        while (remaining > 0 && delay < MAX_RECONNECT_DELAY_MS) {
            delay *= 2
            remaining--
        }
        return delay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    fun disconnect() {
        generation++
        shouldReconnect.set(false)
        reconnectTask?.cancel(true)
        reconnectTask = null
        call?.cancel()
        call = null
        isConnected.set(false)
    }

    fun isConnected(): Boolean = isConnected.get()
}
