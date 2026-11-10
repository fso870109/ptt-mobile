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
import app.ptt.mobile.PttApp
import app.ptt.mobile.net.ImageCompressor
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.widget.ArrayAdapter
import android.widget.Spinner
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

    private var pendingReportShot: ByteArray? = null
    private var reportShotLabel: TextView? = null
    private val pickReportShot = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            try {
                val bytes = ImageCompressor.compress(this, uri, maxBytes = 300 * 1024)
                pendingReportShot = bytes
                runOnUiThread { reportShotLabel?.text = "已選擇截圖（${bytes.size / 1024} KB）" }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "無法處理截圖：${e.message}", Toast.LENGTH_LONG).show() }
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
        val need = talkPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) { startServiceAndBind(); return }
        AlertDialog.Builder(this)
            .setTitle("需要麥克風與通知")
            .setMessage("對講時會使用麥克風，把你的聲音即時傳給頻道或私訊對象，語音不會被錄音保存。通知用來顯示正在收聽／發話的前景狀態。若你使用藍牙耳機或實體對講鍵，也需要「附近的裝置」權限。")
            .setNegativeButton("先不要") { _, _ -> startServiceAndBind() }
            .setPositiveButton("繼續") { _, _ -> permLauncher.launch(need.toTypedArray()) }
            .show()
    }

    private fun talkPermissions(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        return list.toTypedArray()
    }

    private fun locationPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val locLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) engine.setShareLoc(true) else {
            shareLoc.isChecked = false
            Toast.makeText(this, "未允許位置，無法分享", Toast.LENGTH_SHORT).show()
        }
    }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleInvite(contents)
    }

    private val camLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchScanner() else Toast.makeText(this, "未允許相機，無法掃描", Toast.LENGTH_SHORT).show()
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
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { showSettings() }
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
        shareLoc.setOnCheckedChangeListener { _, checked ->
            if (!checked) { engine.setShareLoc(false); return@setOnCheckedChangeListener }
            AlertDialog.Builder(this)
                .setTitle("分享目前位置")
                .setMessage("開啟後，會把你目前的位置與電量分享給同頻道成員及公司管理員／調度員。系統只保留最新一筆，不建立移動軌跡。你可以隨時關閉。")
                .setNegativeButton("取消") { _, _ -> shareLoc.isChecked = false }
                .setPositiveButton("開啟") { _, _ ->
                    val need = locationPermissions().filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (need.isEmpty()) engine.setShareLoc(true)
                    else locLauncher.launch(need.toTypedArray())
                }
                .show()
        }
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


    private fun canCreateInvite(): Boolean {
        val me = engine.state.me
        if (me?.role == "admin" || me?.role == "org_admin") return true
        return engine.state.channels.any { it.myRole == "supervisor" }
    }

    private fun showSettings() {
        val items = mutableListOf("回報問題", "通話紀錄", "掃描邀請碼", "輸入邀請碼", "外觀")
        if (canCreateInvite()) items.add("產生頻道邀請")
        items.addAll(listOf("隱私權政策", "服務條款", "刪除帳號說明", "刪除我的帳號"))
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "回報問題" -> showReportDialog()
                    "通話紀錄" -> showCallLogs()
                    "掃描邀請碼" -> startQrScan()
                    "輸入邀請碼" -> promptInviteCode()
                    "外觀" -> showThemePicker()
                    "產生頻道邀請" -> showCreateInvite()
                    "隱私權政策" -> openLegal("/privacy")
                    "服務條款" -> openLegal("/terms")
                    "刪除帳號說明" -> openLegal("/delete-account")
                    "刪除我的帳號" -> showDeleteAccount()
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun showThemePicker() {
        val modes = arrayOf("跟隨系統", "淺色", "深色")
        val values = arrayOf("system", "light", "dark")
        val cur = PttApp.themeMode(this)
        val checked = values.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("外觀")
            .setSingleChoiceItems(modes, checked) { d, which ->
                PttApp.setThemeMode(this, values[which])
                d.dismiss()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun showCallLogs() {
        thread {
            try {
                val arr = ApiClient().listCalls(engine.prefs.serverUrl, engine.prefs.token)
                val lines = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val name = c.optString("fromName", "使用者")
                    val kind = c.optString("kind")
                    val where = if (kind == "dm") "私訊 · " + c.optString("peerName", "對方") else "#" + c.optString("channelName")
                    val dur = c.optDouble("durMs", 0.0) / 1000.0
                    val pre = if (c.optBoolean("preempted")) " · 被插話" else ""
                    val whenStr = try {
                        java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.TAIWAN)
                            .format(java.util.Date(c.optLong("startedAt")))
                    } catch (_: Exception) { "" }
                    lines.add(name + " → " + where + " · " + whenStr + " · " + String.format("%.1f", dur) + " 秒" + pre)
                }
                runOnUiThread {
                    val msg = if (lines.isEmpty()) "目前沒有通話紀錄。只記錄說話者、對象、時間、長度與是否被插話，不存音訊。"
                    else lines.joinToString("\n\n")
                    AlertDialog.Builder(this)
                        .setTitle("通話紀錄")
                        .setMessage(msg)
                        .setPositiveButton("匯出 CSV") { _, _ -> exportCallsCsv() }
                        .setNegativeButton("關閉", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.message ?: "無法載入", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun exportCallsCsv() {
        thread {
            try {
                val bytes = ApiClient().downloadCallsCsv(engine.prefs.serverUrl, engine.prefs.token)
                val text = bytes.toString(Charsets.UTF_8)
                runOnUiThread {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "Geelo 通話紀錄")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(send, "匯出 CSV"))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.message ?: "無法匯出", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startQrScan() {
        AlertDialog.Builder(this)
            .setTitle("掃描邀請 QR Code")
            .setMessage("接下來會使用相機掃描頻道邀請 QR Code，讓你加入對講頻道。我們不會拍照或上傳畫面。")
            .setNegativeButton("取消", null)
            .setPositiveButton("繼續") { _, _ ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchScanner()
                } else camLauncher.launch(Manifest.permission.CAMERA)
            }
            .show()
    }

    private fun launchScanner() {
        val opt = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("掃描頻道邀請 QR Code")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        qrLauncher.launch(opt)
    }

    private fun promptInviteCode() {
        val input = EditText(this).apply { hint = "邀請碼或完整連結" }
        AlertDialog.Builder(this)
            .setTitle("輸入邀請碼")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("加入") { _, _ -> handleInvite(input.text.toString()) }
            .show()
    }

    private fun handleInvite(raw: String) {
        val code = ApiClient.parseInviteCode(raw)
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "邀請碼格式不正確", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val j = ApiClient().acceptInvite(engine.prefs.serverUrl, engine.prefs.token, code)
                val ch = j.optJSONObject("channel")
                val name = ch?.optString("name") ?: "頻道"
                val id = ch?.optString("id").orEmpty()
                runOnUiThread {
                    Toast.makeText(this, "已加入 $name", Toast.LENGTH_SHORT).show()
                    if (id.isNotBlank()) engine.join(id)
                }
            } catch (e: ApiClient.ApiError) {
                runOnUiThread { Toast.makeText(this, e.display, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.message ?: "無法加入", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showCreateInvite() {
        val chans = engine.state.channels.filter {
            engine.state.me?.role == "admin" || engine.state.me?.role == "org_admin" || it.myRole == "supervisor"
        }
        if (chans.isEmpty()) {
            Toast.makeText(this, "沒有可邀請的頻道", Toast.LENGTH_SHORT).show()
            return
        }
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chans.map { "# " + it.name })
        val kindSpin = Spinner(this)
        val kinds = listOf("加入頻道（已有帳號）" to "join", "邀請註冊（新人開帳號）" to "register")
        kindSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.first })
        val hours = EditText(this).apply { hint = "有效時數（空白＝不限）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val uses = EditText(this).apply { hint = "次數上限（空白＝不限）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(spin); addView(kindSpin); addView(hours); addView(uses)
        }
        AlertDialog.Builder(this)
            .setTitle("產生頻道邀請")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("產生") { _, _ ->
                val ch = chans.getOrNull(spin.selectedItemPosition) ?: return@setPositiveButton
                val kind = kinds[kindSpin.selectedItemPosition].second
                val h = hours.text.toString().toIntOrNull()
                val u = uses.text.toString().toIntOrNull()
                thread {
                    try {
                        val inv = ApiClient().createInvite(engine.prefs.serverUrl, engine.prefs.token, ch.id, kind, h, u)
                        val url = inv.optString("url")
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("邀請已建立")
                                .setMessage(url)
                                .setPositiveButton("複製") { _, _ ->
                                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("invite", url))
                                    Toast.makeText(this, "已複製連結", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("關閉", null)
                                .show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, e.message ?: "無法產生", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .show()
    }


    private fun openLegal(path: String) {
        val root = engine.prefs.serverUrl.trimEnd('/')
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(root + path)))
    }

    private fun showDeleteAccount() {
        val input = EditText(this).apply {
            hint = "輸入目前密碼以確認"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(TextView(this@MainActivity).apply {
                text = "此動作無法復原。我們會刪除你的帳號與個人資料，並將你送出的文字訊息匿名化。語音本來就不會保存。若你是公司最後一位管理員，系統會拒絕刪除。"
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("刪除我的帳號")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("確認刪除") { _, _ ->
                val pw = input.text.toString()
                if (pw.isBlank()) { Toast.makeText(this, "請輸入密碼", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                thread {
                    try {
                        ApiClient().deleteAccount(engine.prefs.serverUrl, engine.prefs.token, pw)
                        runOnUiThread {
                            Toast.makeText(this, "帳號已刪除", Toast.LENGTH_SHORT).show()
                            PttService.stop(this)
                            engine.disconnect(clearAuth = true)
                            goLogin()
                        }
                    } catch (e: ApiClient.ApiError) {
                        runOnUiThread { Toast.makeText(this, e.display, Toast.LENGTH_LONG).show() }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, e.message ?: "刪除失敗", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .show()
    }

    private fun showReportDialog() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val hintView = TextView(this).apply {
            text = "請描述發生了什麼。可選擇附上截圖。系統會自動附上裝置型號、系統、App 版本與最近的連線事件（不含 token、密碼或語音）。"
            textSize = 13f
        }
        val input = EditText(this).apply {
            this.hint = "請描述問題…"
            minLines = 4
            maxLines = 8
        }
        val shotInfo = TextView(this).apply { text = "尚未選擇截圖"; textSize = 12f }
        reportShotLabel = shotInfo
        pendingReportShot = null
        val pick = TextView(this).apply {
            text = "選擇截圖（選填）"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sky))
            setPadding(0, 16, 0, 8)
            setOnClickListener { pickReportShot.launch("image/*") }
        }
        wrap.addView(hintView); wrap.addView(input); wrap.addView(pick); wrap.addView(shotInfo)
        AlertDialog.Builder(this)
            .setTitle("回報問題")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("送出") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "請填寫問題說明", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                engine.submitReport(text, pendingReportShot) { err ->
                    if (err == null) Toast.makeText(this, "已送出回報，謝謝", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }
}
