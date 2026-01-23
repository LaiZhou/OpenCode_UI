package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.*
import ai.opencode.ide.jetbrains.util.PathUtil
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for OpenCode Server API.
 */
open class OpenCodeApiClient(
    private val hostname: String, 
    private val port: Int,
    private val username: String? = null,
    private val password: String? = null
) {

    private val logger = Logger.getInstance(OpenCodeApiClient::class.java)
    private val baseUrl = "http://$hostname:$port"

    // Make client protected so subclasses can potentially use it or override methods
    protected val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .registerTypeAdapter(FileDiff::class.java, FileDiffDeserializer())
        .create()

    fun getSessions(directory: String): List<Session> {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        val url = "$baseUrl/session?directory=${encode(serverPath)}"
        val type = object : TypeToken<List<Session>>() {}.type
        return get(url, type) ?: emptyList()
    }

    fun getSessionStatuses(directory: String): List<SessionStatus> {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        val url = "$baseUrl/session/status?directory=${encode(serverPath)}"
        val mapType = object : TypeToken<Map<String, SessionStatusType>>() {}.type
        val map = get<Map<String, SessionStatusType>>(url, mapType) ?: return emptyList()
        return map.map { (id, statusType) -> SessionStatus(id, statusType) }
    }

    fun findBusySession(directory: String): SessionStatus? {
        return getSessionStatuses(directory).find { it.status.isBusy() }
    }

    open fun getSessionDiff(sessionId: String, directory: String, messageId: String? = null): List<FileDiff> {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        var url = "$baseUrl/session/$sessionId/diff?directory=${encode(serverPath)}"
        if (messageId != null) url += "&messageID=$messageId"
        val type = object : TypeToken<List<FileDiff>>() {}.type
        return get(url, type) ?: emptyList()
    }

    /**
     * Get a specific session by ID.
     */
    open fun getSession(sessionId: String, directory: String): Session? {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        val url = "$baseUrl/session/$sessionId?directory=${encode(serverPath)}"
        return get(url, Session::class.java)
    }

    fun checkHealth(directory: String): Boolean {
        return try {
            val url = "$baseUrl/global/health"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    fun createEventListener(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): SseEventListener {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        return SseEventListener(baseUrl, serverPath, onEvent, onError, onConnected, onDisconnected, username, password)
    }

    fun tuiAppendPrompt(text: String): Boolean {
        val url = "$baseUrl/tui/append-prompt"
        val body = TuiAppendPromptRequest(text)
        return post(url, body) != null
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun <T> get(url: String, clazz: Class<T>): T? {
        val request = Request.Builder().url(url).get().build()
        return execute(request, clazz)
    }

    private fun <T> get(url: String, type: java.lang.reflect.Type): T? {
        val request = Request.Builder().url(url).get().build()
        return execute(request, type)
    }

    private fun post(url: String, body: Any): String? {
        val json = gson.toJson(body)
        val requestBody = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string() ?: ""
                } else {
                    logger.warn("[OpenCode] POST failed: $url (HTTP ${response.code})")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("[OpenCode] POST error: $url", e)
            null
        }
    }

    private fun <T> execute(request: Request, type: java.lang.reflect.Type): T? {
        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    logger.debug("[API] Success: ${request.method} ${request.url} (took ${duration}ms)")
                    // 强制使用 UTF-8 解码，避免服务端未设置 charset 导致中文乱码或内容丢失
                    val bodyBytes = response.body?.bytes()
                    val jsonString = bodyBytes?.toString(Charsets.UTF_8)
                    gson.fromJson(jsonString, type)
                } else {
                    val bodyPreview = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                    logger.warn("[API] Failed: ${request.method} ${request.url} (HTTP ${response.code}, took ${duration}ms). Response: $bodyPreview")
                    null
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.debug("[API] Error: ${request.method} ${request.url} -> ${e.message} (after ${duration}ms)")
            null
        }
    }
    
    private fun <T> execute(request: Request, clazz: Class<T>): T? {
        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    logger.debug("[API] Success: ${request.method} ${request.url} (took ${duration}ms)")
                    // 强制使用 UTF-8 解码，避免服务端未设置 charset 导致中文乱码或内容丢失
                    val bodyBytes = response.body?.bytes()
                    val jsonString = bodyBytes?.toString(Charsets.UTF_8)
                    gson.fromJson(jsonString, clazz)
                } else {
                    val bodyPreview = try { response.peekBody(1024).string() } catch (_: Exception) { "no body" }
                    logger.warn("[API] Failed: ${request.method} ${request.url} (HTTP ${response.code}, took ${duration}ms). Response: $bodyPreview")
                    null
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.debug("[API] Error: ${request.method} ${request.url} -> ${e.message} (after ${duration}ms)")
            null
        }
    }
}
