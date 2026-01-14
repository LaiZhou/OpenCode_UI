package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.*
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
class OpenCodeApiClient(
    private val hostname: String, 
    private val port: Int,
    private val username: String? = null,
    private val password: String? = null
) {

    private val logger = Logger.getInstance(OpenCodeApiClient::class.java)
    private val baseUrl = "http://$hostname:$port"

    private val client = OkHttpClient.Builder()
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
        .create()

    fun getSessions(directory: String): List<Session> {
        val url = "$baseUrl/session?directory=${encode(directory)}"
        val type = object : TypeToken<List<Session>>() {}.type
        return get(url, type) ?: emptyList()
    }

    fun getSessionStatuses(directory: String): List<SessionStatus> {
        val url = "$baseUrl/session/status?directory=${encode(directory)}"
        val mapType = object : TypeToken<Map<String, SessionStatusType>>() {}.type
        val map = get<Map<String, SessionStatusType>>(url, mapType) ?: return emptyList()
        return map.map { (id, statusType) -> SessionStatus(id, statusType) }
    }

    fun findBusySession(directory: String): SessionStatus? {
        return getSessionStatuses(directory).find { it.status.isBusy() }
    }

    fun getSessionDiff(sessionId: String, directory: String, messageId: String? = null): List<FileDiff> {
        var url = "$baseUrl/session/$sessionId/diff?directory=${encode(directory)}"
        if (messageId != null) url += "&messageID=$messageId"
        val type = object : TypeToken<List<FileDiff>>() {}.type
        return get(url, type) ?: emptyList()
    }

    /**
     * Get a specific session by ID.
     */
    fun getSession(sessionId: String, directory: String): Session? {
        val url = "$baseUrl/session/$sessionId?directory=${encode(directory)}"
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
        return SseEventListener(baseUrl, directory, onEvent, onError, onConnected, onDisconnected, username, password)
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
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), type)
                } else {
                    logger.warn("[OpenCode] Request failed: ${request.url} (HTTP ${response.code})")
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("[OpenCode] Request error: ${request.url} -> ${e.message}")
            null
        }
    }
    
    private fun <T> execute(request: Request, clazz: Class<T>): T? {
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), clazz)
                } else {
                    logger.warn("[OpenCode] Request failed: ${request.url} (HTTP ${response.code})")
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("[OpenCode] Request error: ${request.url} -> ${e.message}")
            null
        }
    }
}
