package app.ptt.mobile.engine

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.ptt.mobile.audio.PcmPlayer
import app.ptt.mobile.audio.PcmRecorder
import app.ptt.mobile.loc.LocationReporter
import app.ptt.mobile.model.Alert
import app.ptt.mobile.model.ChatMessage
import app.ptt.mobile.model.Channel
import app.ptt.mobile.model.DmSummary
import app.ptt.mobile.model.JsonX
import app.ptt.mobile.model.LastTalk
import app.ptt.mobile.model.LocEntry
import app.ptt.mobile.model.Member
import app.ptt.mobile.model.OnlineUser
import app.ptt.mobile.model.TalkInfo
import app.ptt.mobile.model.Target
import app.ptt.mobile.model.User
import app.ptt.mobile.net.ApiClient
import app.ptt.mobile.net.WsClient
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.pow

class PttEngine private constructor(private val app: Context) {
    val prefs = Prefs(app)
    private val api = ApiClient()
    private val ws = WsClient()
    private val recorder = PcmRecorder()
    private val player = PcmPlayer()
    private val main = Handler(Looper.getMainLooper())
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val locReporter = LocationReporter(app) { lat, lng, acc, bat, chg ->
        sendLoc(lat, lng, acc, bat, chg)
    }

    data class UiState(
        val conn: String = "off",
        val connText: String = "未連線",
        val me: User? = null,
        val channels: List<Channel> = emptyList(),
        val members: List<Member> = emptyList(),
        val online: List<OnlineUser> = emptyList(),
        val dms: List<DmSummary> = emptyList(),
        val target: Target? = null,
        val channelId: String? = null,
        val talks: Map<String, TalkInfo> = emptyMap(),
        val pressing: Boolean = false,
        val granted: Boolean = false,
        val pttLabel: String = "按住講話",
        val pttMode: String = "idle", // idle, requesting, talking, busy, listen
        val lastTalkText: String = "這裡還沒有人說話",
        val locs: List<LocEntry> = emptyList(),
        val shareLoc: Boolean = false,
        val locStatus: String = "未分享",
        val alerts: List<Alert> = emptyList(),
        val messages: List<ChatMessage> = emptyList(),
        val captionLine: String = "",
        val captionInterim: Boolean = false,
        val dispatchStatus: String = "",
        val dispatchId: String? = null,
        val toast: String? = null,
        val authLost: Boolean = false,
    )

    @Volatile var state = UiState()
        private set

    interface Listener {
        fun onState(s: UiState)
        fun onAlert(a: Alert)
        fun onAlertEnd(id: String, by: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l); main.post { l.onState(state) } }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private var retry = 0
    private var authFailed = false
    private var lastPong = System.currentTimeMillis()
    private var pending = ArrayList<ByteArray>()
    private var pendingConv = ""
    private var streamId = 0
    private val lastTalks = HashMap<String, LastTalk>()
    private var reconnectToken: Runnable? = null
    private var pingToken: Runnable? = null

    private val pingTick = object : Runnable {
        override fun run() {
            if (ws.isOpen) {
                val ts = System.currentTimeMillis()
                ws.sendJson("t" to "ping", "ts" to ts)
                main.postDelayed({
                    if (lastPong < ts && ws.isOpen) {
                        ws.close()
                    }
                }, 8_000)
            }
            pingToken = this
            main.postDelayed(this, 15_000)
        }
    }

    fun connect() {
        authFailed = false
        val token = prefs.token
        if (token.isBlank()) {
            publish(state.copy(conn = "off", connText = "請先登入", authLost = true))
            return
        }
        reconnectToken?.let { main.removeCallbacks(it) }
        val url = ApiClient.wsUrl(prefs.serverUrl)
        val waitText = if (retry > 0) "重新連線中…（第 $retry 次）" else "連線中…"
        publish(state.copy(conn = "wait", connText = waitText, authLost = false))
        ws.connect(url, object : WsClient.Listener {
            override fun onOpen() {
                retry = 0
                ws.sendJson("t" to "hello", "token" to token)
                main.removeCallbacks(pingTick)
                main.post(pingTick)
            }
            override fun onJson(msg: JSONObject) = handle(msg)
            override fun onBinary(data: ByteArray) = handleBinary(data)
            override fun onClosed(code: Int, reason: String) = onDisco(code, reason)
            override fun onFailure(t: Throwable) = onDisco(1006, t.message ?: "fail")
        })
    }

    fun disconnect(clearAuth: Boolean = false) {
        reconnectToken?.let { main.removeCallbacks(it) }
        main.removeCallbacks(pingTick)
        internalStopTalk(sendStop = true)
        if (prefs.shareLoc) {
            ws.sendJson("t" to "loc_off")
            locReporter.stop()
        }
        ws.close()
        player.stop()
        if (clearAuth) prefs.clearSession()
        publish(state.copy(conn = "off", connText = if (clearAuth) "已登出" else "未連線", talks = emptyMap()))
    }

    fun join(channelId: String) {
        ws.sendJson("t" to "join", "channel" to channelId)
    }

    fun openDm(peerId: String, peerName: String) {
        if (peerId == state.me?.id) return
        val ids = listOf(state.me?.id ?: "", peerId).sorted()
        val conv = "dm:${ids[0]}:${ids[1]}"
        publish(state.copy(target = Target.DmT(peerId, peerName, conv)).withPttLabel())
        loadMessagesFor(conv)
    }

    fun backToChannel() {
        val ch = state.channels.find { it.id == state.channelId }
        val t = if (ch != null) Target.ChannelT(ch.id, ch.name) else state.target
        publish(state.copy(target = t).withPttLabel())
        loadMessagesFor(t?.conv())
    }

    fun sendChat(text: String) {
        val conv = state.target?.conv() ?: run { toast("請先選擇頻道"); return }
        val t = text.trim()
        if (t.isEmpty()) return
        if (t.length > 2000) { toast("文字最多 2000 字"); return }
        ws.sendJson("t" to "chat", "conv" to conv, "text" to t)
    }

    fun callDispatch() {
        if (state.me?.role != "user") { toast("調度員請從網頁調度台接聽來電"); return }
        if (state.conn != "on") { toast("尚未連線"); return }
        ws.sendJson("t" to "dispatch_call")
    }

    fun cancelDispatch() {
        val id = state.dispatchId ?: return
        ws.sendJson("t" to "dispatch_done", "id" to id)
    }

    fun uploadPhoto(jpeg: ByteArray) {
        val conv = state.target?.conv() ?: run { toast("請先選擇頻道"); return }
        thread {
            try {
                api.uploadPhoto(prefs.serverUrl, prefs.token, conv, jpeg)
            } catch (e: ApiClient.ApiError) {
                main.post { toast(e.display) }
            } catch (e: Exception) {
                main.post { toast("照片上傳失敗：${e.message}") }
            }
        }
    }

    fun loadMessagesFor(conv: String?) {
        if (conv.isNullOrBlank() || prefs.token.isBlank()) return
        thread {
            try {
                val list = api.listMessages(prefs.serverUrl, prefs.token, conv)
                main.post {
                    if (state.target?.conv() == conv) publish(state.copy(messages = list))
                }
            } catch (_: Exception) {}
        }
    }

    fun startTalk(@Suppress("UNUSED_PARAMETER") source: String = "ui") {
        val s = state
        if (s.pressing) return
        if (s.conn != "on") { toast("尚未連線"); return }
        val target = s.target ?: run { toast("請先選擇頻道"); return }
        if (myRole() == "listener" && myAlert() == null) {
            toast("你在這個頻道是「只聽」角色，不能發話")
            return
        }
        val conv = target.conv()
        pending = ArrayList()
        pendingConv = conv
        streamId = 0
        publish(s.copy(pressing = true, granted = false, pttMode = "requesting", pttLabel = "連線中…"))
        vibrate(20)
        trySco(true)
        val started = recorder.start { pcm ->
            if (state.granted && ws.isOpen) {
                ws.sendBinary(pcm)
            } else if (state.pressing && !state.granted) {
                synchronized(pending) {
                    pending.add(pcm)
                    if (pending.size > 30) pending.removeAt(0)
                }
            }
        }
        if (!started) {
            toast("無法開啟麥克風")
            internalStopTalk(sendStop = false)
            return
        }
        when (target) {
            is Target.DmT -> ws.sendJson("t" to "ptt_start", "to" to target.peerId)
            is Target.ChannelT -> ws.sendJson("t" to "ptt_start", "conv" to target.id)
        }
    }

    fun stopTalk(@Suppress("UNUSED_PARAMETER") source: String = "ui") {
        if (!state.pressing) return
        internalStopTalk(sendStop = true)
    }

    fun setShareLoc(on: Boolean) {
        prefs.shareLoc = on
        if (on) {
            locReporter.start()
            publish(state.copy(shareLoc = true, locStatus = "定位中…"))
        } else {
            locReporter.stop()
            ws.sendJson("t" to "loc_off")
            publish(state.copy(shareLoc = false, locStatus = "未分享", locs = state.locs))
        }
    }

    fun requestLocs() { ws.sendJson("t" to "get_locs") }

    fun fireAlert() {
        if (state.conn != "on") { toast("尚未連線"); return }
        vibrate(200)
        val last = state.locs.find { it.id == state.me?.id }
        val o = JSONObject().put("t", "alert_start")
        if (last != null) {
            o.put("lat", last.lat).put("lng", last.lng)
            last.acc?.let { o.put("acc", it) }
        } else {
            // best-effort last known from reporter battery-only is not enough
        }
        ws.sendJson(o)
    }

    fun resolveAlert(id: String) {
        ws.sendJson("t" to "alert_resolve", "id" to id)
    }

    fun consumeToast() {
        if (state.toast != null) publish(state.copy(toast = null))
    }

    private fun internalStopTalk(sendStop: Boolean) {
        recorder.stop()
        trySco(false)
        if (sendStop && ws.isOpen) ws.sendJson("t" to "ptt_stop")
        synchronized(pending) { pending.clear() }
        publish(state.copy(pressing = false, granted = false).withPttLabel())
    }

    private fun handleBinary(data: ByteArray) {
        if (data.size < 4) return
        val sid = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val pcm = data.copyOfRange(4, data.size)
        player.play(sid, pcm)
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "pong" -> lastPong = System.currentTimeMillis()
            "auth_failed" -> {
                authFailed = true
                prefs.clearSession()
                internalStopTalk(sendStop = false)
                ws.close()
                publish(state.copy(conn = "off", connText = "請重新登入", authLost = true, toast = denyAuth(m.optString("reason"))))
            }
            "welcome" -> {
                retry = 0
                lastPong = System.currentTimeMillis()
                val user = JsonX.user(m.getJSONObject("user"))
                val channels = JsonX.channels(m.optJSONArray("channels"))
                val dms = JsonX.dms(m.optJSONArray("dms"))
                val online = JsonX.onlines(m.optJSONArray("online"))
                val alerts = JsonX.alerts(m.optJSONArray("alerts"))
                channels.forEach { c -> c.lastTalk?.let { lastTalks[c.id] = it } }
                publish(
                    state.copy(
                        conn = "on",
                        connText = "已連線",
                        me = user,
                        channels = channels,
                        dms = dms,
                        online = online,
                        alerts = alerts,
                        authLost = false,
                    ).withPttLabel()
                )
                val want = prefs.channel.ifBlank { "lobby" }
                val ok = channels.find { it.id == want }?.id ?: channels.firstOrNull()?.id
                if (ok != null) join(ok)
                if (prefs.shareLoc) {
                    locReporter.start()
                    publish(state.copy(shareLoc = true, locStatus = "定位中…"))
                }
            }
            "channels" -> {
                val channels = JsonX.channels(m.optJSONArray("channels"))
                channels.forEach { c -> c.lastTalk?.let { lastTalks[c.id] = it } }
                val t = refreshChannelTarget(channels, state.target)
                publish(state.copy(channels = channels, target = t).withPttLabel())
            }
            "joined" -> {
                val chId = if (m.has("channel") && !m.isNull("channel")) m.optString("channel") else null
                if (chId.isNullOrBlank()) {
                    publish(state.copy(channelId = null, target = null, toast = "你已沒有任何頻道的權限").withPttLabel())
                    return
                }
                if (m.optString("reason") == "access_changed") toast("頻道權限已變更，已移回「大廳」")
                prefs.channel = chId
                val ch = state.channels.find { it.id == chId }
                val keepDm = state.target is Target.DmT
                val target = if (keepDm) state.target else Target.ChannelT(chId, ch?.name ?: chId)
                publish(state.copy(channelId = chId, target = target).withPttLabel())
                if (!keepDm) loadMessagesFor(chId)
            }
            "members" -> {
                if (m.optString("channel") == state.channelId) {
                    publish(state.copy(members = JsonX.members(m.optJSONArray("members"))))
                }
            }
            "online" -> publish(state.copy(online = JsonX.onlines(m.optJSONArray("online"))))
            "floor_granted" -> {
                val conv = m.optString("conv")
                if (!state.pressing || conv != pendingConv) {
                    ws.sendJson("t" to "ptt_stop")
                    return
                }
                streamId = m.optInt("streamId")
                val toFlush: List<ByteArray>
                synchronized(pending) {
                    toFlush = pending.toList()
                    pending.clear()
                }
                publish(state.copy(granted = true, pttMode = "talking", pttLabel = "說話中… 放開結束"))
                for (p in toFlush) ws.sendBinary(p)
                vibrate(40)
            }
            "floor_denied" -> {
                val reason = m.optString("reason")
                val byName = m.optString("byName")
                internalStopTalk(sendStop = false)
                when (reason) {
                    "busy" -> {
                        publish(state.copy(pttMode = "busy", pttLabel = "${byName.ifBlank { "有人" }} 正在說話"))
                        vibrate(30)
                        main.postDelayed({ publish(state.withPttLabel()) }, 1500)
                    }
                    "offline" -> toast("${byName.ifBlank { "對方" }} 目前不在線（不保存錄音，無法留言）")
                    "listen_only" -> toast("你在這個頻道是「只聽」角色，不能發話")
                    "no_access" -> toast("你沒有在這裡發話的權限")
                    else -> toast("無法發話：$reason")
                }
            }
            "floor_revoked" -> {
                val reason = m.optString("reason")
                val byName = m.optString("byName")
                internalStopTalk(sendStop = false)
                when (reason) {
                    "preempted" -> toast("⚠️ 你的發話被 $byName 中斷（優先權較高）")
                    "stalled" -> toast("網路不穩，語音中斷，已結束發話")
                    else -> toast("已達單次發話上限 60 秒")
                }
            }
            "talk_start" -> {
                val conv = m.optString("conv")
                val info = TalkInfo(
                    from = m.optString("from"),
                    fromName = m.optString("fromName"),
                    streamId = m.optInt("streamId"),
                    prio = m.optInt("prio"),
                    conv = conv,
                )
                val talks = state.talks.toMutableMap()
                talks[conv] = info
                publish(state.copy(talks = talks).withPttLabel())
                if (conv.startsWith("dm:") && state.target?.conv() != conv) {
                    toast("📞 ${info.fromName} 私人對講中")
                }
            }
            "talk_end" -> {
                val conv = m.optString("conv")
                val sid = m.optInt("streamId")
                val talks = state.talks.toMutableMap()
                val cur = talks[conv]
                if (cur != null && cur.streamId == sid) talks.remove(conv)
                player.endStream(sid)
                val lt = if (m.has("lastTalk") && !m.isNull("lastTalk")) JsonX.lastTalk(m.optJSONObject("lastTalk")) else null
                if (lt != null) lastTalks[conv] = lt
                if (m.optString("reason") == "preempted" && m.optString("from") != state.me?.id) {
                    toast("${nameOf(m.optString("from"))} 被 ${m.optString("byName")} 中斷")
                }
                var dms = state.dms
                if (lt != null && conv.startsWith("dm:")) dms = upsertDm(dms, conv, lt)
                val clearCap = conv == state.target?.conv()
                publish(state.copy(talks = talks, dms = dms, captionLine = if (clearCap) "" else state.captionLine, captionInterim = if (clearCap) false else state.captionInterim).withPttLabel())
            }
            "channel_activity" -> {
                val id = m.optString("channel")
                val lt = if (m.has("lastTalk") && !m.isNull("lastTalk")) JsonX.lastTalk(m.optJSONObject("lastTalk")) else null
                if (lt != null) lastTalks[id] = lt
                val channels = state.channels.map {
                    if (it.id == id) it.copy(last = m.optLong("last"), lastTalk = lt ?: it.lastTalk) else it
                }
                publish(state.copy(channels = channels).withPttLabel())
            }
            "loc" -> {
                val loc = m.optJSONObject("loc") ?: return
                val e = JsonX.loc(loc)
                val locs = state.locs.filter { it.id != e.id } + e
                publish(state.copy(locs = locs.sortedByDescending { it.ts }))
            }
            "locs" -> publish(state.copy(locs = JsonX.locs(m.optJSONArray("locs")).sortedByDescending { it.ts }))
            "loc_remove" -> publish(state.copy(locs = state.locs.filter { it.id != m.optString("id") }))
            "alert" -> {
                val a = JsonX.alert(m.getJSONObject("alert"))
                val alerts = state.alerts.filter { it.id != a.id } + a
                publish(state.copy(alerts = alerts))
                listeners.forEach { it.onAlert(a) }
            }
            "alert_end" -> {
                val id = m.optString("id")
                val by = m.optString("by")
                publish(state.copy(alerts = state.alerts.filter { it.id != id }, toast = "✅ 警報已由 $by 解除"))
                listeners.forEach { it.onAlertEnd(id, by) }
            }
            "error" -> toast(m.optString("msg").ifBlank { m.optString("code") })
            "chat" -> {
                val msgObj = m.optJSONObject("message") ?: return
                val msg = JsonX.chat(msgObj)
                val list = if (state.target?.conv() == msg.conv) {
                    (state.messages.filter { it.id != msg.id } + msg).sortedBy { it.ts }
                } else state.messages
                publish(state.copy(messages = list))
            }
            "caption" -> {
                val conv = m.optString("conv")
                val name = m.optString("fromName")
                val text = m.optString("text")
                val interim = m.optBoolean("interim")
                val line = (if (name.isNotBlank()) "$name：" else "") + text
                val relevant = conv == state.target?.conv() || conv == state.channelId || state.talks.containsKey(conv)
                if (relevant) publish(state.copy(captionLine = line, captionInterim = interim))
            }
            "queue_self" -> {
                val item = m.optJSONObject("item")
                val id = item?.optString("id")
                val status = item?.optString("status") ?: "waiting"
                val text = if (status == "held") "調度台已接聽" else "正在等候調度台…"
                publish(state.copy(dispatchId = id, dispatchStatus = text, toast = text))
            }
            "dispatch_pickup" -> {
                val conv = m.optString("conv")
                val by = m.optString("by")
                val byName = m.optString("byName")
                val item = m.optJSONObject("item")
                val id = item?.optString("id") ?: state.dispatchId
                if (conv.isNotBlank() && by.isNotBlank() && state.me?.role == "user") {
                    val target = Target.DmT(by, byName.ifBlank { "調度台" }, conv)
                    publish(state.copy(target = target, dispatchId = id, dispatchStatus = "調度台已接聽").withPttLabel())
                    loadMessagesFor(conv)
                    toast("調度台已接聽")
                } else {
                    publish(state.copy(dispatchId = id, dispatchStatus = "調度台已接聽"))
                }
            }
            "dispatch_transfer" -> {
                val conv = m.optString("conv")
                val toName = m.optString("toName")
                val to = m.optString("to")
                if (conv.isNotBlank() && to.isNotBlank()) {
                    publish(state.copy(
                        target = Target.DmT(to, toName.ifBlank { "調度台" }, conv),
                        dispatchStatus = "已轉接給 $toName"
                    ).withPttLabel())
                    loadMessagesFor(conv)
                }
                toast("通話已轉接給 ${toName.ifBlank { "其他調度員" }}")
            }
            "dispatch_done" -> {
                publish(state.copy(dispatchId = null, dispatchStatus = "", toast = "調度台通話已結束"))
            }
        }
    }

    private fun onDisco(code: Int, reason: String) {
        main.removeCallbacks(pingTick)
        if (state.pressing) internalStopTalk(sendStop = false)
        if (authFailed || code == 4001 || code == 4003) {
            publish(state.copy(conn = "off", connText = "請重新登入", talks = emptyMap(), authLost = true))
            return
        }
        val delay = min(10_000.0, 1000 * 1.6.pow(retry.toDouble())).toLong()
        retry += 1
        publish(state.copy(conn = "off", connText = "已斷線，${(delay / 1000)} 秒後重連", talks = emptyMap()))
        val r = Runnable { connect() }
        reconnectToken = r
        main.postDelayed(r, delay)
    }

    private fun sendLoc(lat: Double, lng: Double, acc: Float?, battery: Double?, charging: Boolean?) {
        if (!ws.isOpen) return
        val o = JSONObject()
            .put("t", "loc")
            .put("lat", lat)
            .put("lng", lng)
        if (acc != null) o.put("acc", acc.toDouble())
        if (battery != null) o.put("battery", battery)
        if (charging != null) o.put("charging", charging)
        ws.sendJson(o)
        val pct = battery?.let { "${kotlin.math.round(it * 100).toInt()}%" } ?: "不明"
        publish(state.copy(shareLoc = true, locStatus = "已回報 · 電量 $pct"))
    }

    private fun upsertDm(dms: List<DmSummary>, conv: String, lt: LastTalk): List<DmSummary> {
        val myId = state.me?.id ?: return dms
        val peerId = conv.removePrefix("dm:").split(":").firstOrNull { it != myId } ?: myId
        val peerName = if (lt.from == peerId) lt.fromName else (lt.toName ?: dms.find { it.conv == conv }?.peerName ?: "對方")
        val rest = dms.filter { it.conv != conv }
        return (rest + DmSummary(conv, peerId, peerName, lt.ts, lt)).sortedByDescending { it.last }
    }

    private fun refreshChannelTarget(channels: List<Channel>, target: Target?): Target? {
        val t = target as? Target.ChannelT ?: return target
        val ch = channels.find { it.id == t.id } ?: return t
        return if (ch.name != t.name) Target.ChannelT(ch.id, ch.name) else t
    }

    private fun myRole(): String? = state.roleHere()
    private fun myAlert(): Alert? = state.ownAlert()

    private fun UiState.roleHere(): String? {
        val t = target
        if (t == null || t is Target.DmT) return "member"
        return channels.find { it.id == (t as Target.ChannelT).id }?.myRole
    }

    private fun UiState.ownAlert(): Alert? = alerts.find { it.userId == me?.id }

    private fun nameOf(id: String): String =
        state.online.find { it.id == id }?.name ?: state.members.find { it.id == id }?.name ?: "某人"

    private fun UiState.withPttLabel(): UiState {
        val listen = roleHere() == "listener" && ownAlert() == null
        val conv = target?.conv()
        val busy = conv?.let { talks[it] }?.takeIf { it.from != me?.id }
        val last = conv?.let { lastTalks[it] }
        val lastText = if (last == null) "這裡還沒有人說話"
        else "最近說話：${if (last.from == me?.id) "我" else last.fromName} · ${fmtDur(last.durMs)}"
        if (pressing && granted) return copy(pttMode = "talking", pttLabel = "說話中… 放開結束", lastTalkText = lastText)
        if (pressing) return copy(pttMode = "requesting", pttLabel = "連線中…", lastTalkText = lastText)
        val label = when {
            listen -> "只聽（不能發話）"
            busy != null -> "${busy.fromName} 正在說話"
            else -> "按住講話"
        }
        val mode = when {
            listen -> "listen"
            busy != null -> "busy"
            else -> "idle"
        }
        return copy(pttMode = mode, pttLabel = label, lastTalkText = lastText)
    }

    private fun fmtDur(ms: Long): String = String.format("%.1f 秒", ms / 1000.0)

    private fun toast(msg: String) { publish(state.copy(toast = msg)) }

    private fun denyAuth(reason: String) = when (reason) {
        "revoked" -> "你的帳號已被停用或密碼已重設，請重新登入"
        "org_inactive" -> "此公司已停用或方案到期，請重新登入"
        else -> "登入已過期，請重新登入"
    }

    private fun publish(s: UiState) {
        state = s
        main.post { listeners.forEach { it.onState(s) } }
    }

    private fun vibrate(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {}
    }

    private fun trySco(on: Boolean) {
        try {
            if (on) {
                if (am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
            } else {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        } catch (_: Exception) {}
    }

    fun talkStatusText(): String {
        val conv = state.target?.conv()
        return state.talks.values.filter { it.from != state.me?.id }.joinToString("　") { info ->
            if (info.conv.startsWith("dm:")) "🔊 ${info.fromName} 正在私下對你說話"
            else {
                val extra = if (info.conv != conv) {
                    val n = state.channels.find { it.id == info.conv }?.name ?: ""
                    "（$n）"
                } else ""
                "🔊 ${info.fromName} 正在說話$extra"
            }
        }
    }

    companion object {
        @Volatile private var inst: PttEngine? = null
        fun get(ctx: Context): PttEngine =
            inst ?: synchronized(this) { inst ?: PttEngine(ctx.applicationContext).also { inst = it } }
    }
}
