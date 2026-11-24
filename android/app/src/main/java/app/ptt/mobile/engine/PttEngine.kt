package app.ptt.mobile.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        val talkMode: String = "hold",   // 設定：hold / lock / vox
        val talkKind: String = "hold",   // 這次發話的方式
        val locked: Boolean = false,     // 鎖定講話中
        val voxArmed: Boolean = false,   // 聲控待命中
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
    private var connectGen = 0
    private var authFailed = false
    private var lastPong = System.currentTimeMillis()
    private var lastRtt: Long? = null
    private var pending = ArrayList<ByteArray>()
    private val chatQueue = ArrayList<Pair<String, String>>()
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNetKind = ""
    private var pendingConv = ""
    private var streamId = 0
    private val lastTalks = HashMap<String, LastTalk>()
    private var reconnectToken: Runnable? = null

    // ---- 持續說話（鎖定講話／聲控）----
    /** 即時音量（0～100）與是否超過聲控門檻；只給畫面上的音量表用，不走 publish 以免頻繁重繪 */
    @Volatile var levelListener: ((Int, Boolean) -> Unit)? = null
    @Volatile private var micOn = false
    @Volatile private var voxArmedFlag = false
    @Volatile private var levelTest = false
    private var voxConv = ""
    private var voxAboveSince = 0L
    private var voxLastLoud = 0L
    private var voxCooldownUntil = 0L
    private var voxNeedSilence = false
    private val preroll = ArrayList<ByteArray>()
    private var downAt = 0L
    private var keyDown = false
    private var ignoreUp = false
    private var manualInVox = false
    private var maxMs = 60_000L
    private var talkStartedAt = 0L
    private val holdRunnable = Runnable { if (keyDown && state.talkMode == "vox") { manualInVox = true; startTalk("hold") } }
    private val talkTick = object : Runnable {
        override fun run() {
            if (!state.granted) return
            if (System.currentTimeMillis() - talkStartedAt > maxMs + 2_000) {
                EventLog.add("talk", "local max reached")
                internalStopTalk(sendStop = true)
                toast("已達單次發話上限 ${maxMs / 1000} 秒")
                return
            }
            publish(state.withPttLabel())
            main.postDelayed(this, 1_000)
        }
    }
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

    init {
        state = state.copy(talkMode = prefs.talkMode)
    }

    fun connect() {
        authFailed = false
        val token = prefs.token
        if (token.isBlank()) {
            publish(state.copy(conn = "off", connText = "請先登入", authLost = true))
            return
        }
        reconnectToken?.let { main.removeCallbacks(it) }
        watchNetwork()
        connectGen += 1
        val url = ApiClient.wsUrl(prefs.serverUrl)
        val waitText = if (retry > 0) "重新連線中…（第 $retry 次）" else "連線中…"
        publish(state.copy(conn = "wait", connText = waitText, authLost = false))
        EventLog.add("ws", if (retry > 0) "reconnect attempt $retry" else "connecting")
        ws.connect(url, object : WsClient.Listener {
            override fun onOpen() {
                EventLog.add("ws", "open, hello+resume")
                val hello = org.json.JSONObject().put("t", "hello").put("token", token)
                if (prefs.channel.isNotBlank()) hello.put("resume", org.json.JSONObject().put("channel", prefs.channel))
                ws.sendJson(hello)
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
        internalStopTalk(sendStop = true)
        disarmVox(null)
        levelTest = false
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
        afterTargetChange()
        loadMessagesFor(conv)
    }

    fun backToChannel() {
        val ch = state.channels.find { it.id == state.channelId }
        val t = if (ch != null) Target.ChannelT(ch.id, ch.name) else state.target
        publish(state.copy(target = t).withPttLabel())
        afterTargetChange()
        loadMessagesFor(t?.conv())
    }

    fun sendChat(text: String, convOpt: String? = null) {
        val conv = convOpt ?: state.target?.conv() ?: run { toast("請先選擇頻道"); return }
        val t = text.trim()
        if (t.isEmpty()) return
        if (t.length > 2000) { toast("文字最多 2000 字"); return }
        if (state.conn != "on" || !ws.isOpen) {
            synchronized(chatQueue) { chatQueue.add(conv to t) }
            EventLog.add("chat", "queued while offline n=${chatQueue.size}")
            toast("目前斷線，文字會在重連後自動送出（${chatQueue.size}）")
            return
        }
        ws.sendJson("t" to "chat", "conv" to conv, "text" to t)
        EventLog.add("chat", "sent")
    }

    private fun flushChatQueue() {
        val q: List<Pair<String, String>>
        synchronized(chatQueue) {
            q = chatQueue.toList()
            chatQueue.clear()
        }
        if (q.isEmpty()) return
        EventLog.add("chat", "flush ${q.size}")
        for ((conv, text) in q) sendChat(text, conv)
    }

    fun submitReport(text: String, screenshotJpeg: ByteArray?, onDone: (String?) -> Unit) {
        thread {
            try {
                val meta = org.json.JSONObject()
                    .put("platform", "android")
                    .put("device", android.os.Build.MODEL)
                    .put("os", "Android " + android.os.Build.VERSION.RELEASE)
                    .put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "")
                    .put("browser", "")
                    .put("userAgent", "app.ptt.mobile")
                api.submitReport(prefs.serverUrl, prefs.token, text, screenshotJpeg, meta, EventLog.dump())
                main.post { EventLog.add("report", "submitted"); onDone(null) }
            } catch (e: ApiClient.ApiError) {
                main.post { onDone(e.display) }
            } catch (e: Exception) {
                main.post { onDone(e.message ?: "送出失敗") }
            }
        }
    }

    fun forceReconnect(reason: String) {
        EventLog.add("net", reason)
        retry = 0
        if (state.granted) toast("偵測到網路切換，已安全結束發話並重新連線")
        internalStopTalk(sendStop = true)
        reconnectToken?.let { main.removeCallbacks(it) }
        ws.close()
        connect()
    }

    private fun watchNetwork() {
        if (netCb != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val kind = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    else -> "other"
                }
                EventLog.add("net", "available $kind")
                if (lastNetKind.isNotEmpty() && lastNetKind != kind) {
                    main.post { forceReconnect("network $lastNetKind->$kind") }
                }
                lastNetKind = kind
            }
            override fun onLost(network: Network) { EventLog.add("net", "lost") }
        }
        netCb = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
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

    /** 相容舊呼叫（耳機、通知等）：直接開始一段發話 */
    fun startTalk(source: String = "ui") { startTalkKind(if (state.talkMode == "lock") "lock" else "hold", source) }

    private fun startTalkKind(kind: String, source: String): Boolean {
        val s = state
        if (s.pressing) return false
        if (s.conn != "on") { if (kind != "vox") toast("尚未連線"); return false }
        val target = s.target ?: run { if (kind != "vox") toast("請先選擇頻道"); return false }
        if (myRole() == "listener" && myAlert() == null) {
            if (kind != "vox") toast("你在這個頻道是「只聽」角色，不能發話")
            return false
        }
        val conv = target.conv()
        synchronized(pending) {
            pending = ArrayList()
            if (kind == "vox") synchronized(preroll) { pending.addAll(preroll); preroll.clear() }
        }
        pendingConv = conv
        streamId = 0
        publish(s.copy(pressing = true, granted = false, talkKind = kind, pttMode = "requesting", pttLabel = "連線中…"))
        if (kind != "vox") vibrate(20)
        if (!ensureMic()) {
            toast("無法開啟麥克風")
            internalStopTalk(sendStop = false)
            return false
        }
        EventLog.add("talk", "start $kind $source")
        when (target) {
            is Target.DmT -> ws.sendJson("t" to "ptt_start", "to" to target.peerId, "mode" to kind)
            is Target.ChannelT -> ws.sendJson("t" to "ptt_start", "conv" to target.id, "mode" to kind)
        }
        return true
    }

    fun stopTalk(@Suppress("UNUSED_PARAMETER") source: String = "ui") {
        if (!state.pressing && !state.locked) return
        internalStopTalk(sendStop = true)
    }

    /** 主按鈕或實體 PTT 鍵按下：依「講話方式」決定行為 */
    fun pttDown(source: String = "ui") {
        if (keyDown) return
        keyDown = true; downAt = System.currentTimeMillis(); ignoreUp = false
        val s = state
        if (s.locked) { EventLog.add("talk", "unlock tap"); internalStopTalk(sendStop = true); ignoreUp = true; return }
        when (s.talkMode) {
            "vox" -> {
                if (s.pressing && s.talkKind == "vox") { internalStopTalk(sendStop = true); voxNeedSilence = true }
                main.removeCallbacks(holdRunnable)
                main.postDelayed(holdRunnable, TAP_MS)
            }
            "lock" -> startTalkKind("lock", source)
            else -> startTalkKind("hold", source)
        }
    }

    /** 主按鈕或實體 PTT 鍵放開 */
    fun pttUp(@Suppress("UNUSED_PARAMETER") source: String = "ui") {
        if (!keyDown) return
        keyDown = false
        main.removeCallbacks(holdRunnable)
        if (ignoreUp) { ignoreUp = false; return }
        val quick = System.currentTimeMillis() - downAt < TAP_MS
        when (state.talkMode) {
            "vox" -> {
                if (manualInVox) { manualInVox = false; internalStopTalk(sendStop = true); return }
                if (quick) toggleVox()
            }
            "lock" -> {
                if (quick && state.pressing) {
                    EventLog.add("talk", "locked")
                    vibrate(30)
                    publish(state.copy(locked = true, talkKind = "lock").withPttLabel())
                } else internalStopTalk(sendStop = true)
            }
            else -> internalStopTalk(sendStop = true)
        }
    }

    /** 通知列／耳機的「發話／結束」切換：等同鎖定講話 */
    fun toggleLock(source: String = "notification") {
        if (state.pressing || state.locked) { internalStopTalk(sendStop = true); return }
        if (startTalkKind("lock", source)) publish(state.copy(locked = true).withPttLabel())
    }

    fun setTalkMode(mode: String) {
        if (mode !in Prefs.TALK_MODES) return
        if (state.pressing || state.locked) internalStopTalk(sendStop = true)
        if (mode != "vox") disarmVox(null)
        prefs.talkMode = mode
        publish(state.copy(talkMode = mode).withPttLabel())
    }

    fun setVoxSens(v: Int) { prefs.voxSens = v }
    fun voxThresholdDb(): Double = -20.0 - prefs.voxSens * 0.4
    fun dbToPct(db: Double): Int = (((db + 60.0) / 60.0) * 100).toInt().coerceIn(0, 100)

    fun toggleVox() { if (voxArmedFlag) disarmVox("聲控已關閉") else armVox() }

    fun armVox(): Boolean {
        if (voxArmedFlag) return true
        val t = state.target ?: run { toast("請先加入頻道"); return false }
        if (myRole() == "listener" && myAlert() == null) { toast("你在這個頻道是「只聽」角色，不能使用聲控"); return false }
        voxArmedFlag = true
        voxConv = t.conv(); voxAboveSince = 0; voxLastLoud = 0; voxCooldownUntil = 0; voxNeedSilence = false
        synchronized(preroll) { preroll.clear() }
        if (!ensureMic()) { voxArmedFlag = false; toast("無法開啟麥克風"); return false }
        EventLog.add("talk", "vox armed")
        publish(state.copy(voxArmed = true, toast = "聲控已開啟：有說話聲就會自動發送").withPttLabel())
        return true
    }

    fun disarmVox(msg: String?) {
        if (!voxArmedFlag) return
        voxArmedFlag = false
        synchronized(preroll) { preroll.clear() }
        if (state.pressing && state.talkKind == "vox") internalStopTalk(sendStop = true)
        EventLog.add("talk", "vox off")
        releaseMicIfIdle()
        levelListener?.invoke(0, false)
        publish(state.copy(voxArmed = false, toast = msg ?: state.toast).withPttLabel())
    }

    /** 設定頁「測試麥克風音量」 */
    fun setLevelTest(on: Boolean): Boolean {
        levelTest = on
        if (on) return ensureMic()
        releaseMicIfIdle(); return true
    }

    private fun ensureMic(): Boolean {
        if (micOn) return true
        trySco(true)
        val ok = recorder.start { pcm -> onMicFrame(pcm) }
        micOn = ok
        if (!ok) trySco(false)
        return ok
    }

    private fun releaseMicIfIdle() {
        if (!micOn || state.pressing || voxArmedFlag || levelTest) return
        recorder.stop()
        micOn = false
        trySco(false)
    }

    /** 麥克風每 100ms 一包（錄音執行緒） */
    private fun onMicFrame(pcm: ByteArray) {
        val st = state
        if (st.pressing && st.granted && ws.isOpen) {
            ws.sendBinary(pcm)
        } else if (st.pressing && !st.granted) {
            synchronized(pending) {
                pending.add(pcm)
                if (pending.size > 30) pending.removeAt(0)
            }
        } else if (voxArmedFlag) {
            synchronized(preroll) {
                preroll.add(pcm)
                while (preroll.size > VOX_PREROLL) preroll.removeAt(0)
            }
        }
        if (voxArmedFlag || levelTest) {
            val db = rmsDb(pcm)
            val over = db > voxThresholdDb()
            val pct = dbToPct(db)
            main.post {
                levelListener?.invoke(pct, over)
                if (voxArmedFlag) voxTick(db)
            }
        }
    }

    private fun rmsDb(pcm: ByteArray): Double {
        var sum = 0.0
        val n = pcm.size / 2
        if (n == 0) return -100.0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768.0
            sum += v * v
            i += 2
        }
        return 20 * kotlin.math.log10(kotlin.math.sqrt(sum / n) + 1e-9)
    }

    private fun canVoxTalk(): Boolean {
        val s = state
        if (s.conn != "on" || s.target == null) return false
        if (myRole() == "listener" && myAlert() == null) return false
        val busy = s.target.conv().let { s.talks[it] }
        return !(busy != null && busy.from != s.me?.id) // 別人說話時不觸發，也避免把喇叭聲當成自己說話
    }

    private fun voxTick(db: Double) {
        val now = System.currentTimeMillis()
        val loud = db > voxThresholdDb()
        if (loud) voxLastLoud = now
        if (voxNeedSilence) {
            if (!loud && now - voxLastLoud > 1_000) voxNeedSilence = false
            voxAboveSince = 0
            return
        }
        val s = state
        if (s.pressing) {
            if (s.talkKind == "vox" && now - voxLastLoud > VOX_HANG_MS) {
                EventLog.add("talk", "vox hang release")
                internalStopTalk(sendStop = true)
            }
            return
        }
        if (!loud) { voxAboveSince = 0; return }
        if (voxAboveSince == 0L) voxAboveSince = now
        if (now - voxAboveSince >= VOX_ATTACK_MS && now >= voxCooldownUntil && canVoxTalk()) {
            voxAboveSince = 0
            startTalkKind("vox", "vox")
        }
    }

    /** 換頻道或換私人對講：結束進行中的鎖定／聲控發話，並關閉聲控，避免講到別的地方 */
    private fun afterTargetChange() {
        val conv = state.target?.conv() ?: ""
        if ((state.pressing || state.locked) && pendingConv.isNotEmpty() && pendingConv != conv) {
            EventLog.add("talk", "stop on switch")
            internalStopTalk(sendStop = true)
        }
        if (voxArmedFlag && voxConv != conv) disarmVox("已切換對話，聲控已關閉；需要時請再開啟")
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
        main.removeCallbacks(talkTick)
        talkStartedAt = 0
        val was = state.pressing
        if (sendStop && was && ws.isOpen) ws.sendJson("t" to "ptt_stop")
        synchronized(pending) { pending.clear() }
        state = state.copy(pressing = false, granted = false, locked = false)
        releaseMicIfIdle()
        publish(state.withPttLabel())
    }

    private fun handleBinary(data: ByteArray) {
        if (data.size < 4) return
        val sid = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val pcm = data.copyOfRange(4, data.size)
        player.play(sid, pcm)
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "pong" -> {
                lastPong = System.currentTimeMillis()
                val ts = m.optLong("ts", 0L)
                if (ts > 0) lastRtt = (System.currentTimeMillis() - ts).coerceAtLeast(0)
                publish(state.copy(conn = "on", connText = connQualityText()))
            }
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
                EventLog.add("ws", "welcome resumed=" + m.optString("resumedChannel"))
                val user = JsonX.user(m.getJSONObject("user"))
                val channels = JsonX.channels(m.optJSONArray("channels"))
                val dms = JsonX.dms(m.optJSONArray("dms"))
                val online = JsonX.onlines(m.optJSONArray("online"))
                val alerts = JsonX.alerts(m.optJSONArray("alerts"))
                channels.forEach { c -> c.lastTalk?.let { lastTalks[c.id] = it } }
                publish(
                    state.copy(
                        conn = "on",
                        connText = connQualityText(),
                        me = user,
                        channels = channels,
                        dms = dms,
                        online = online,
                        alerts = alerts,
                        authLost = false,
                    ).withPttLabel()
                )
                val resumed = m.optString("resumedChannel")
                val want = prefs.channel.ifBlank { resumed.ifBlank { "lobby" } }
                val ok = channels.find { it.id == want }?.id ?: channels.firstOrNull()?.id
                if (ok != null && ok != resumed) join(ok)
                if (prefs.shareLoc) {
                    locReporter.start()
                    publish(state.copy(shareLoc = true, locStatus = "定位中…"))
                }
                flushChatQueue()
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
                    afterTargetChange()
                    return
                }
                if (m.optString("reason") == "access_changed") toast("頻道權限已變更，已移回「大廳」")
                prefs.channel = chId
                val ch = state.channels.find { it.id == chId }
                val keepDm = state.target is Target.DmT
                val target = if (keepDm) state.target else Target.ChannelT(chId, ch?.name ?: chId)
                publish(state.copy(channelId = chId, target = target).withPttLabel())
                afterTargetChange()
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
                maxMs = m.optLong("maxMs", 60_000L).coerceAtLeast(5_000L)
                talkStartedAt = System.currentTimeMillis()
                main.removeCallbacks(talkTick)
                main.postDelayed(talkTick, 1_000)
                val toFlush: List<ByteArray>
                synchronized(pending) {
                    toFlush = pending.toList()
                    pending.clear()
                }
                publish(state.copy(granted = true).withPttLabel())
                for (p in toFlush) ws.sendBinary(p)
                vibrate(40)
            }
            "floor_denied" -> {
                val reason = m.optString("reason")
                val byName = m.optString("byName")
                val kind = state.talkKind
                internalStopTalk(sendStop = false)
                if (kind == "vox" || m.optString("mode") == "vox") {
                    // 聲控：不跳干擾提示，稍等再試；冷卻中要先安靜一下
                    voxCooldownUntil = System.currentTimeMillis() + if (reason == "cooldown") m.optLong("retryMs", 3_000L) else 1_500L
                    if (reason == "cooldown") voxNeedSilence = true
                    if (reason != "busy" && reason != "cooldown") toast("聲控無法發話：" + when (reason) { "listen_only" -> "你是「只聽」角色"; "offline" -> "對方不在線"; else -> reason })
                    return
                }
                when (reason) {
                    "cooldown" -> toast("剛達到發話上限，請等 ${(m.optLong("retryMs", 3_000L) + 999) / 1000} 秒再講，讓其他人有機會說話")
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
                val kind = state.talkKind
                if (keyDown) ignoreUp = true // 還按著也要停，放開時不要當成「點一下」
                manualInVox = false
                internalStopTalk(sendStop = false)
                if (kind == "vox" || voxArmedFlag) {
                    voxNeedSilence = true
                    voxCooldownUntil = System.currentTimeMillis() + m.optLong("cooldownMs", 1_500L)
                }
                when (reason) {
                    "preempted" -> { toast("⚠️ 你的發話被 $byName 中斷（優先權較高）"); vibrate(120) }
                    "stalled" -> toast("網路不穩，語音中斷，已結束發話")
                    else -> toast("已達單次發話上限 ${m.optLong("maxMs", maxMs) / 1000} 秒，已自動結束，讓其他人說話")
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
        EventLog.add("ws", "close code=$code $reason")
        val wasGranted = state.granted
        if (state.pressing || state.granted) {
            internalStopTalk(sendStop = false)
            if (wasGranted) toast("連線中斷，已安全結束發話（發言權已釋放）")
            else toast("連線中斷，發話尚未送出")
        }
        if (authFailed || code == 4001 || code == 4003) {
            publish(state.copy(conn = "off", connText = "請重新登入", talks = emptyMap(), authLost = true))
            return
        }
        val base = min(10_000.0, 1000 * 1.6.pow(retry.toDouble()))
        val delay = (base * (0.7 + Math.random() * 0.6)).toLong()
        retry += 1
        publish(state.copy(conn = "off", connText = "已斷線，${(delay / 1000)} 秒後重連", talks = emptyMap()))
        val genAtClose = connectGen
        val r = Runnable { if (connectGen == genAtClose) connect() }
        reconnectToken = r
        main.postDelayed(r, delay)
    }

    private fun connQualityText(): String {
        val rtt = lastRtt ?: return "已連線"
        val q = when {
            rtt < 80 -> "訊號良好（${rtt} ms）"
            rtt < 200 -> "訊號尚可（${rtt} ms）"
            else -> "訊號較弱（${rtt} ms）"
        }
        return "已連線 · $q"
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
        if (pressing && granted) {
            val left = if (talkStartedAt > 0) ((maxMs - (System.currentTimeMillis() - talkStartedAt)) / 1000).coerceAtLeast(0) else maxMs / 1000
            val warn = if (talkStartedAt > 0 && left <= 10) "（剩 $left 秒）" else ""
            val lbl = when {
                talkKind == "vox" -> "🗣️ 聲控發話中$warn"
                locked -> "🔒 鎖定講話中$warn\n再按一下結束"
                else -> "說話中… 放開結束$warn"
            }
            return copy(pttMode = if (locked) "locked" else "talking", pttLabel = lbl, lastTalkText = lastText)
        }
        if (pressing) return copy(pttMode = "requesting", pttLabel = if (locked) "🔒 鎖定中，等待發言權…" else "連線中…", lastTalkText = lastText)
        val idle = when (talkMode) {
            "lock" -> "點一下開始講\n長按也能講"
            "vox" -> if (voxArmed) "🎧 聲控待命中\n點一下關閉" else "點一下開啟聲控\n長按可直接講"
            else -> "按住講話"
        }
        val label = when {
            listen -> "只聽（不能發話）"
            busy != null -> "${busy.fromName} 正在說話"
            else -> idle
        }
        val mode = when {
            listen -> "listen"
            busy != null -> "busy"
            voxArmed -> "vox"
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
        const val TAP_MS = 400L          // 鎖定模式：按下不到這個時間就放開＝鎖定；按久＝按住講話
        const val VOX_ATTACK_MS = 80L    // 音量超過門檻持續這麼久才發話
        const val VOX_HANG_MS = 900L     // 安靜這麼久才放掉發言權
        const val VOX_PREROLL = 3        // 發話前保留約 0.3 秒聲音
        @Volatile private var inst: PttEngine? = null
        fun get(ctx: Context): PttEngine =
            inst ?: synchronized(this) { inst ?: PttEngine(ctx.applicationContext).also { inst = it } }
    }
}
