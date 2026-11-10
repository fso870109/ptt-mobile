package app.ptt.mobile.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WsClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
) {
    interface Listener {
        fun onOpen() {}
        fun onJson(msg: JSONObject)
        fun onBinary(data: ByteArray)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable)
    }

    @Volatile private var socket: WebSocket? = null
    private val opened = AtomicBoolean(false)

    val isOpen: Boolean get() = opened.get() && socket != null

    fun connect(url: String, listener: Listener) {
        close()
        val req = Request.Builder().url(url).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                listener.onOpen()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (_: Exception) { return }
                listener.onJson(json)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBinary(bytes.toByteArray())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket && socket != null) return
                opened.set(false)
                if (socket === webSocket) socket = null
                listener.onClosed(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket && socket != null) return
                opened.set(false)
                if (socket === webSocket) socket = null
                listener.onFailure(t)
            }
        })
    }

    fun sendJson(obj: JSONObject): Boolean {
        val s = socket ?: return false
        return s.send(obj.toString())
    }

    fun sendJson(vararg pairs: Pair<String, Any?>): Boolean {
        val o = JSONObject()
        for ((k, v) in pairs) {
            if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
        }
        return sendJson(o)
    }

    fun sendBinary(pcm: ByteArray): Boolean {
        val s = socket ?: return false
        return s.send(pcm.toByteString())
    }

    fun close() {
        opened.set(false)
        socket?.close(1000, "bye")
        socket = null
    }
}
