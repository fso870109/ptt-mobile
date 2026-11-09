package app.ptt.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.ptt.mobile.R
import app.ptt.mobile.engine.PttEngine
import app.ptt.mobile.model.Alert
import app.ptt.mobile.model.Target
import app.ptt.mobile.net.ApiClient
import app.ptt.mobile.net.ImageCompressor
import app.ptt.mobile.service.PttKeys
import app.ptt.mobile.service.PttService
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), PttEngine.Listener {
    private lateinit var engine: PttEngine
    private val handler = Handler(Looper.getMainLooper())
    private var sosRunnable: Runnable? = null
    private var shownAlerts = HashSet<String>()

    private lateinit var connDot: View
    private lateinit var connText: TextView
    private lateinit var meName: TextView
    private lateinit var alertBanner: TextView
    private lateinit var channelList: LinearLayout
    private lateinit var memberList: LinearLayout
    private lateinit var otherList: LinearLayout
    private lateinit var dmList: LinearLayout
    private lateinit var locList: LinearLayout
    private lateinit var panelChannels: View
    private lateinit var panelMembers: View
    private lateinit var panelDms: View
    private lateinit var panelChat: View
    private lateinit var panelLoc: View
    private lateinit var tabChannels: TextView
    private lateinit var tabMembers: TextView
    private lateinit var tabDms: TextView
    private lateinit var tabChat: TextView
    private lateinit var tabLoc: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var captionBanner: TextView
    private lateinit var dispatchBtn: TextView
    private lateinit var targetLabel: TextView
    private lateinit var roleBadge: TextView
    private lateinit var backToChannel: TextView
    private lateinit var talkStatus: TextView
    private lateinit var pttBtn: TextView
    private lateinit var lastTalk: TextView
    private lateinit var sosBtn: TextView
    private lateinit var shareLoc: MaterialSwitch
    private lateinit var locStatus: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startServiceAndBind() }

    private val photoCache = HashMap<String, Bitmap>()
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            try {
                val bytes = ImageCompressor.compress(this, uri)
                engine.uploadPhoto(bytes)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "照片上傳失敗：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PttEngine.get(this)
        if (engine.prefs.token.isBlank()) {
            goLogin()
            return
        }
        setContentView(R.layout.activity_main)
        bindViews()
        setupUi()
        engine.addListener(this)
        requestPermsThenStart()
    }

    override fun onDestroy() {
        engine.removeListener(this)
        super.onDestroy()
    }

    private fun requestPermsThenStart() {
        val need = ArrayList<String>()
        fun want(p: String) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        want(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want(Manifest.permission.POST_NOTIFICATIONS)
        want(Manifest.permission.ACCESS_FINE_LOCATION)
        want(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) want(Manifest.permission.BLUETOOTH_CONNECT)
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray()) else startServiceAndBind()
    }

    private fun startServiceAndBind() {
        PttService.start(this)
    }

    private fun bindViews() {
        connDot = findViewById(R.id.connDot)
        connText = findViewById(R.id.connText)
        meName = findViewById(R.id.meName)
        alertBanner = findViewById(R.id.alertBanner)
        channelList = findViewById(R.id.channelList)
        memberList = findViewById(R.id.memberList)
        otherList = findViewById(R.id.otherList)
        dmList = findViewById(R.id.dmList)
        locList = findViewById(R.id.locList)
        panelChannels = findViewById(R.id.panelChannels)
        panelMembers = findViewById(R.id.panelMembers)
        panelDms = findViewById(R.id.panelDms)
        panelChat = findViewById(R.id.panelChat)
        panelLoc = findViewById(R.id.panelLoc)
        tabChannels = findViewById(R.id.tabChannels)
        tabMembers = findViewById(R.id.tabMembers)
        tabDms = findViewById(R.id.tabDms)
        tabChat = findViewById(R.id.tabChat)
        tabLoc = findViewById(R.id.tabLoc)
        messageList = findViewById(R.id.messageList)
        chatInput = findViewById(R.id.chatInput)
        captionBanner = findViewById(R.id.captionBanner)
        dispatchBtn = findViewById(R.id.dispatchBtn)
        targetLabel = findViewById(R.id.targetLabel)
        roleBadge = findViewById(R.id.roleBadge)
        backToChannel = findViewById(R.id.backToChannel)
        talkStatus = findViewById(R.id.talkStatus)
        pttBtn = findViewById(R.id.pttBtn)
        lastTalk = findViewById(R.id.lastTalk)
        sosBtn = findViewById(R.id.sosBtn)
        shareLoc = findViewById(R.id.shareLoc)
        locStatus = findViewById(R.id.locStatus)
    }

    private fun setupUi() {
        findViewById<TextView>(R.id.logout).setOnClickListener {
            val prefs = engine.prefs
            val url = prefs.serverUrl
            val token = prefs.token
            thread { ApiClient().logout(url, token) }
            PttService.stop(this)
            engine.disconnect(clearAuth = true)
            goLogin()
        }
        val tabs = listOf(
            tabChannels to panelChannels, tabMembers to panelMembers,
            tabDms to panelDms, tabChat to panelChat, tabLoc to panelLoc
        )
        fun select(sel: TextView) {
            for ((tab, panel) in tabs) {
                val on = tab === sel
                tab.isSelected = on
                panel.visibility = if (on) View.VISIBLE else View.GONE
            }
            if (sel === tabLoc) engine.requestLocs()
            if (sel === tabChat) engine.loadMessagesFor(engine.state.target?.conv())
        }
        tabChannels.setOnClickListener { select(tabChannels) }
        tabMembers.setOnClickListener { select(tabMembers) }
        tabDms.setOnClickListener { select(tabDms) }
        tabChat.setOnClickListener { select(tabChat) }
        tabLoc.setOnClickListener { select(tabLoc) }
        select(tabChannels)

        findViewById<TextView>(R.id.sendChat).setOnClickListener {
            engine.sendChat(chatInput.text.toString())
            chatInput.setText("")
        }
        findViewById<TextView>(R.id.sendPhoto).setOnClickListener { pickImage.launch("image/*") }
        dispatchBtn.setOnClickListener {
            if (engine.state.dispatchId != null && engine.state.dispatchStatus.contains("等候")) engine.cancelDispatch()
            else engine.callDispatch()
        }

        pttBtn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    engine.startTalk("ui")
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    engine.stopTalk("ui")
                    true
                }
                else -> false
            }
        }

        sosBtn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sosBtn.text = "繼續按住… 放開取消"
                    val r = Runnable {
                        sosRunnable = null
                        sosBtn.text = "🚨 警報已發出"
                        engine.fireAlert()
                        handler.postDelayed({ resetSos() }, 2500)
                    }
                    sosRunnable = r
                    handler.postDelayed(r, 2000)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sosRunnable != null) {
                        handler.removeCallbacks(sosRunnable!!)
                        sosRunnable = null
                        Toast.makeText(this, "已取消（需長按 2 秒）", Toast.LENGTH_SHORT).show()
                        resetSos()
                    }
                    true
                }
                else -> false
            }
        }

        backToChannel.setOnClickListener { engine.backToChannel() }
        shareLoc.setOnCheckedChangeListener { _, checked -> engine.setShareLoc(checked) }
        alertBanner.setOnClickListener { engine.state.alerts.firstOrNull()?.let { showAlert(it) } }
    }

    private fun resetSos() {
        sosBtn.text = if (engine.state.alerts.any { it.userId == engine.state.me?.id })
            "🚨 警報進行中（點此查看）" else "🚨 長按 2 秒發出緊急警報"
    }

    override fun onState(s: PttEngine.UiState) {
        if (s.authLost && engine.prefs.token.isBlank()) {
            goLogin()
            return
        }
        connText.text = s.connText
        connDot.setBackgroundResource(
            when (s.conn) {
                "on" -> R.drawable.bg_ptt
                "wait" -> R.drawable.bg_ptt_busy
                else -> R.drawable.bg_ptt_listen
            }
        )
        meName.text = s.me?.let { it.displayName + if (it.role == "admin") "（管理員）" else "" } ?: ""
        targetLabel.text = s.target?.label() ?: "尚未加入頻道"
        val role = when (val t = s.target) {
            is Target.ChannelT -> s.channels.find { it.id == t.id }?.myRoleLabel
            else -> null
        }
        if (role.isNullOrBlank()) roleBadge.visibility = View.GONE
        else {
            roleBadge.visibility = View.VISIBLE
            roleBadge.text = "我的角色：$role"
        }
        backToChannel.visibility = if (s.target is Target.DmT) View.VISIBLE else View.GONE
        talkStatus.text = engine.talkStatusText()
        lastTalk.text = s.lastTalkText
        if (s.captionLine.isBlank()) {
            captionBanner.visibility = View.GONE
        } else {
            captionBanner.visibility = View.VISIBLE
            captionBanner.text = (if (s.captionInterim) "字幕（辨識中）：" else "字幕：") + s.captionLine
        }
        val isCaller = s.me?.role == "user"
        dispatchBtn.visibility = if (isCaller) View.VISIBLE else View.GONE
        dispatchBtn.text = when {
            s.dispatchStatus.contains("等候") -> s.dispatchStatus + "（點此取消）"
            s.dispatchStatus.isNotBlank() -> s.dispatchStatus
            else -> "呼叫調度台"
        }
        pttBtn.text = s.pttLabel
        pttBtn.setBackgroundResource(
            when (s.pttMode) {
                "talking" -> R.drawable.bg_ptt_talk
                "busy", "requesting" -> R.drawable.bg_ptt_busy
                "listen" -> R.drawable.bg_ptt_listen
                else -> R.drawable.bg_ptt
            }
        )
        if (shareLoc.isChecked != s.shareLoc) shareLoc.isChecked = s.shareLoc
        locStatus.text = s.locStatus

        renderRows(channelList, s.channels.map { c ->
            val lt = c.lastTalk?.let { " · 最近：${it.fromName}" } ?: ""
            Triple("# ${c.name}  ${c.myRoleLabel}", "${c.online} 人在線$lt") {
                if (c.id == s.channelId) engine.backToChannel() else engine.join(c.id)
            }
        }.ifEmpty { listOf(Triple("你目前沒有任何頻道，請聯絡管理員", "", {})) })

        renderRows(memberList, s.members.map { m ->
            val you = if (m.id == s.me?.id) "（你）" else ""
            Triple("🟢 ${m.name}$you  ${roleLabel(m.role)}", if (m.id == s.me?.id) "" else "私人對講 ›") {
                if (m.id != s.me?.id) engine.openDm(m.id, m.name)
            }
        }.ifEmpty { listOf(Triple("（沒有人）", "", {})) })

        val memberIds = s.members.map { it.id }.toSet()
        val others = s.online.filter { it.id != s.me?.id && it.id !in memberIds }
        renderRows(otherList, others.map { u ->
            Triple("🟢 ${u.name}", "私人對講 ›") { engine.openDm(u.id, u.name) }
        }.ifEmpty { listOf(Triple("（沒有）", "", {})) })

        renderRows(dmList, s.dms.map { d ->
            Triple("🔒 ${d.peerName}", if (d.last > 0) "最近有對講" else "") { engine.openDm(d.peerId, d.peerName) }
        }.ifEmpty { listOf(Triple("還沒有私人對講。到「成員」點一個人即可開始。", "", {})) })

        renderRows(locList, s.locs.map { l ->
            val bat = when {
                l.battery == null -> "電量不明"
                l.charging == true -> "電量 ${l.battery}%⚡"
                else -> "電量 ${l.battery}%"
            }
            val you = if (l.id == s.me?.id) "（你）" else ""
            Triple("📍 ${l.name}$you · $bat", "${l.channelName ?: ""} · ${if (l.online) "在線" else "離線"}") {
                val uri = Uri.parse("geo:${l.lat},${l.lng}?q=${l.lat},${l.lng}(${Uri.encode(l.name)})")
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
                    val web = Uri.parse("https://www.openstreetmap.org/?mlat=${l.lat}&mlon=${l.lng}#map=17/${l.lat}/${l.lng}")
                    startActivity(Intent(Intent.ACTION_VIEW, web))
                }
            }
        }.ifEmpty { listOf(Triple(if (s.me?.role == "admin") "還沒有人分享位置" else "同頻道還沒有人分享位置", "", {})) })

        renderMessages(s)

        if (s.alerts.isEmpty()) {
            alertBanner.visibility = View.GONE
        } else {
            alertBanner.visibility = View.VISIBLE
            alertBanner.text = "🚨 " + s.alerts.joinToString("；") {
                val who = if (it.userId == s.me?.id) "你" else it.name
                who + " 發出緊急警報" + (it.channelName?.let { n -> "（$n）" } ?: "")
            } + " — 點此查看"
            s.alerts.firstOrNull { it.id !in shownAlerts }?.let { showAlert(it) }
        }
        resetSos()

        s.toast?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            engine.consumeToast()
        }
    }

    override fun onAlert(a: Alert) { showAlert(a) }
    override fun onAlertEnd(id: String, by: String) { shownAlerts.remove(id) }

    private fun showAlert(a: Alert) {
        shownAlerts.add(a.id)
        val mine = a.userId == engine.state.me?.id
        val where = buildString {
            if (!a.channelName.isNullOrBlank()) append("頻道：${a.channelName}　")
            if (a.lat != null && a.lng != null) {
                append("位置：${"%.5f".format(a.lat)}, ${"%.5f".format(a.lng)}")
                if (a.acc != null) append("（±${a.acc}m）")
            } else append("位置：不明（未取得定位）")
        }
        val canResolve = engine.state.me?.role == "admin" || mine
        val b = AlertDialog.Builder(this)
            .setTitle(if (mine) "你已發出緊急警報" else "${a.name} 發出緊急警報")
            .setMessage(where + if (mine) "\n警報期間你擁有最高發話權" else "")
            .setPositiveButton("知道了", null)
        if (a.lat != null && a.lng != null) {
            b.setNeutralButton("打開地圖") { _, _ ->
                val web = Uri.parse("https://www.openstreetmap.org/?mlat=${a.lat}&mlon=${a.lng}#map=17/${a.lat}/${a.lng}")
                startActivity(Intent(Intent.ACTION_VIEW, web))
            }
        }
        if (canResolve) {
            b.setNegativeButton(if (mine) "取消我的警報" else "解除警報") { _, _ -> engine.resolveAlert(a.id) }
        }
        b.show()
    }

    private fun renderMessages(s: PttEngine.UiState) {
        messageList.removeAllViews()
        if (s.messages.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.item_row, messageList, false)
            empty.findViewById<TextView>(R.id.title).text = "這個對話還沒有文字或照片"
            empty.findViewById<TextView>(R.id.meta).text = "在下方輸入後按「送出」，或點「照片」上傳（約 200KB 以內）"
            messageList.addView(empty)
            return
        }
        for (m in s.messages) {
            val v = layoutInflater.inflate(R.layout.item_message, messageList, false)
            val who = if (m.from == s.me?.id) "我" else m.fromName
            val kind = when (m.kind) {
                "photo" -> "照片"
                "caption" -> "字幕"
                else -> "文字"
            }
            v.findViewById<TextView>(R.id.msgMeta).text = "$who · $kind"
            val textView = v.findViewById<TextView>(R.id.msgText)
            if (m.text.isNullOrBlank()) {
                textView.visibility = View.GONE
            } else {
                textView.text = m.text
            }
            val img = v.findViewById<ImageView>(R.id.msgPhoto)
            val pid = m.photoId
            if (m.kind == "photo" && !pid.isNullOrBlank()) {
                img.visibility = View.VISIBLE
                bindPhoto(img, pid)
            }
            messageList.addView(v)
        }
    }

    private fun bindPhoto(iv: ImageView, photoId: String) {
        photoCache[photoId]?.let { iv.setImageBitmap(it); return }
        thread {
            try {
                val bytes = ApiClient().getPhoto(engine.prefs.serverUrl, engine.prefs.token, photoId)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@thread
                photoCache[photoId] = bmp
                runOnUiThread { iv.setImageBitmap(bmp) }
            } catch (_: Exception) {
                runOnUiThread { iv.visibility = View.GONE }
            }
        }
    }

    private fun renderRows(parent: LinearLayout, rows: List<Triple<String, String, () -> Unit>>) {
        parent.removeAllViews()
        for ((title, meta, click) in rows) {
            val v = layoutInflater.inflate(R.layout.item_row, parent, false)
            v.findViewById<TextView>(R.id.title).text = title
            val metaView = v.findViewById<TextView>(R.id.meta)
            if (meta.isBlank()) metaView.visibility = View.GONE else metaView.text = meta
            v.setOnClickListener { click() }
            parent.addView(v)
        }
    }

    private fun roleLabel(r: String) = when (r) {
        "supervisor" -> "主管"
        "listener" -> "只聽"
        else -> "一般"
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (PttKeys.isPttKey(keyCode) && event.repeatCount == 0) {
            engine.startTalk("hw")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (PttKeys.isPttKey(keyCode)) {
            engine.stopTalk("hw")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
