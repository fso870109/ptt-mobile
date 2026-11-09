package app.ptt.mobile.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient(private val http: OkHttpClient = defaultClient()) {
    data class LoginResult(val token: String, val userJson: JSONObject)
    data class ApiError(val status: Int, val error: String, override val message: String) : RuntimeException(message)

    fun login(baseUrl: String, username: String, password: String, company: String?): LoginResult {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
        if (!company.isNullOrBlank()) body.put("company", company.trim())
        val json = request(baseUrl, "POST", "/api/login", body, null)
        val token = json.optString("token")
        if (token.isBlank()) throw ApiError(200, "no_token", "伺服器沒有回傳 token")
        return LoginResult(token, json.getJSONObject("user"))
    }

    fun logout(baseUrl: String, token: String) {
        try { request(baseUrl, "POST", "/api/logout", JSONObject(), token) } catch (_: Exception) {}
    }

    fun me(baseUrl: String, token: String): JSONObject = request(baseUrl, "GET", "/api/me", null, token)

    fun health(baseUrl: String): JSONObject = request(baseUrl, "GET", "/api/health", null, null)

    private fun request(baseUrl: String, method: String, path: String, json: JSONObject?, token: String?): JSONObject {
        val url = baseUrl.trim().trimEnd('/') + path
        val builder = Request.Builder().url(url)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val media = "application/json; charset=utf-8".toMediaType()
        when (method) {
            "POST" -> builder.post((json ?: JSONObject()).toString().toRequestBody(media))
            else -> builder.get()
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = try { JSONObject(text.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            if (!resp.isSuccessful) {
                throw ApiError(
                    resp.code,
                    parsed.optString("error", "http_${resp.code}"),
                    parsed.optString("message", "HTTP ${resp.code}"),
                )
            }
            return parsed
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()

        fun wsUrl(httpUrl: String): String {
            val u = httpUrl.trim().trimEnd('/')
            return when {
                u.startsWith("https://", true) -> "wss://" + u.substring(8) + "/ws"
                u.startsWith("http://", true) -> "ws://" + u.substring(7) + "/ws"
                u.startsWith("wss://", true) || u.startsWith("ws://", true) -> u.trimEnd('/') + "/ws"
                else -> "wss://$u/ws"
            }
        }
    }
}
