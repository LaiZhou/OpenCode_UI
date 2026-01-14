package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEventDeserializer
import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import okhttp3.*
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
        private const val SSE_RECONNECT_DELAY_MS = 3000L
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

    fun connect() {
        if (isConnected.get()) return
        shouldReconnect.set(true)
        doConnect()
    }

    private fun doConnect() {
        val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
        val url = "$baseUrl/event?directory=$encodedPath"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        call = client.newCall(request)
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isConnected.set(false)
                logger.warn("[OpenCode] SSE connection failure: ${e.message}")
                onError(e)
                onDisconnected()
                if (shouldReconnect.get()) scheduleReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isConnected.set(false)
                    logger.error("[OpenCode] SSE handshake failed: HTTP ${response.code}")
                    onError(IOException("SSE failed: ${response.code}"))
                    onDisconnected()
                    if (shouldReconnect.get()) scheduleReconnect()
                    return
                }

                isConnected.set(true)
                onConnected()
                logger.info("[OpenCode] SSE connection established")

                response.body?.source()?.let { source ->
                    try {
                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data:")) {
                                val json = line.substring(5).trim()
                                if (json.isNotEmpty()) parseAndDispatchEvent(json)
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) logger.warn("[OpenCode] SSE stream error: ${e.message}")
                    } finally {
                        isConnected.set(false)
                        onDisconnected()
                        if (shouldReconnect.get() && !call.isCanceled()) scheduleReconnect()
                    }
                }
            }
        })
    }

    private fun parseAndDispatchEvent(json: String) {
        try {
            val event = gson.fromJson(json, OpenCodeEvent::class.java)
            if (event != null) onEvent(event)
        } catch (e: Exception) {
            logger.debug("[OpenCode] SSE parse error: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectTask?.cancel(false)
        reconnectTask = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (shouldReconnect.get()) doConnect()
        }, SSE_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun disconnect() {
        shouldReconnect.set(false)
        reconnectTask?.cancel(true)
        call?.cancel()
        isConnected.set(false)
    }

    fun isConnected(): Boolean = isConnected.get()
}
