package app.ptt.mobile.net

import app.ptt.mobile.model.ChatMessage
import app.ptt.mobile.model.JsonX
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ApiErrors {
    fun message(error: String, fallback: String): String = when (error) {
        "need_org" -> fallback.ifBlank { "請輸入公司代碼" }
        "invalid_credentials" -> fallback.ifBlank { "帳號、密碼或公司代碼錯誤" }
        "disabled" -> fallback.ifBlank { "此帳號已停用" }
        "org_disabled" -> fallback.ifBlank { "此公司已被停用" }
        "org_expired" -> fallback.ifBlank { "此公司方案已到期，請聯絡平台管理員" }
        "org_inactive" -> fallback.ifBlank { "此公司已停用或方案到期" }
        "seat_limit" -> fallback.ifBlank { "已達此公司方案的人數上限" }
        "too_many_attempts" -> fallback.ifBlank { "嘗試次數過多，請稍後再試" }
        "too_many" -> fallback.ifBlank { "回報過於頻繁，請一小時後再試" }
        "too_large" -> fallback.ifBlank { "截圖請壓縮到約 300KB 以內" }
        else -> fallback.ifBlank { error }
    }
}

class ApiClient(private val http: OkHttpClient = defaultClient()) {
    data class LoginResult(val token: String, val userJson: JSONObject)
    data class ApiError(val status: Int, val error: String, override val message: String) : RuntimeException(message) {
        val display: String get() = ApiErrors.message(error, message)
    }

    fun login(baseUrl: String, username: String, password: String, org: String): LoginResult {
        val code = org.trim().uppercase()
        if (code.isBlank()) throw ApiError(400, "need_org", "請輸入公司代碼")
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("org", code)
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

    fun listMessages(baseUrl: String, token: String, conv: String): List<ChatMessage> {
        val q = URLEncoder.encode(conv, "UTF-8")
        val json = request(baseUrl, "GET", "/api/messages?conv=$q&limit=100", null, token)
        return JsonX.chats(json.optJSONArray("messages"))
    }

    fun uploadPhoto(baseUrl: String, token: String, conv: String, jpeg: ByteArray): ChatMessage {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val body = JSONObject().put("conv", conv).put("data", b64)
        val json = request(baseUrl, "POST", "/api/photos", body, token)
        return JsonX.chat(json.getJSONObject("message"))
    }

    fun submitReport(baseUrl: String, token: String, text: String, screenshotJpeg: ByteArray?, meta: JSONObject, log: List<String>): JSONObject {
        val arr = org.json.JSONArray()
        log.forEach { arr.put(it) }
        val body = JSONObject().put("text", text).put("meta", meta).put("log", arr)
        if (screenshotJpeg != null) body.put("screenshot", android.util.Base64.encodeToString(screenshotJpeg, android.util.Base64.NO_WRAP))
        return request(baseUrl, "POST", "/api/reports", body, token)
    }

    fun getPhoto(baseUrl: String, token: String, photoId: String): ByteArray {
        val url = baseUrl.trim().trimEnd('/') + "/api/photos/" + photoId
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, "photo", "無法載入照片（${resp.code}）")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

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
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
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
