import Foundation
import UIKit
import CoreLocation
import Combine
import Network

/// Shared protocol engine used by the SwiftUI UI and the PushToTalk callbacks.
@MainActor
final class PttEngine: NSObject, ObservableObject, WebSocketClientDelegate, CLLocationManagerDelegate {
    static let shared = PttEngine()

    @Published var serverUrl: String
    @Published var company: String
    @Published var username: String
    @Published var token: String
    @Published var connText = "未連線"
    @Published var conn = "off"
    @Published var me: User?
    @Published var channels: [Channel] = []
    @Published var members: [Member] = []
    @Published var online: [OnlineUser] = []
    @Published var dms: [DmSummary] = []
    @Published var target: TalkTarget?
    @Published var channelId: String?
    @Published var talks: [String: TalkInfo] = [:]
    @Published var pressing = false
    @Published var granted = false
    @Published var pttLabel = "按住講話"
    @Published var pttMode = "idle"
    @Published var lastTalkText = "這裡還沒有人說話"
    @Published var locs: [LocEntry] = []
    @Published var shareLoc = false
    @Published var locStatus = "未分享"
    @Published var alerts: [AlertInfo] = []
    @Published var messages: [ChatMessage] = []
    @Published var captionLine = ""
    @Published var captionInterim = false
    @Published var dispatchStatus = ""
    @Published var dispatchId: String?
    @Published var toast: String?
    @Published var loginError: String?

    private let ws = WebSocketClient()
    private let audio = PcmAudio()
    private let defaults = UserDefaults.standard
    private var retry = 0
    private var connectGen = 0
    private var authFailed = false
    private var lastPong = Date()
    private var lastRtt: Int?
    private var pending: [Data] = []
    private var pendingConv = ""
    private var chatQueue: [(String, String)] = []
    private var pathMonitor: NWPathMonitor?
    private var lastPath = ""
    private var lastTalks: [String: LastTalk] = [:]
    private var pingTimer: Timer?
    private var reconnectWork: DispatchWorkItem?
    private let locManager = CLLocationManager()
    private var lastFix: CLLocation?
    private var locTimer: Timer?
    private var useSystemPTT = true

    override init() {
        serverUrl = defaults.string(forKey: "serverUrl") ?? "https://ptt-prototype.onrender.com"
        company = {
            let v = defaults.string(forKey: "company") ?? ""
            return v.isEmpty ? "DEMO" : v
        }()
        username = defaults.string(forKey: "username") ?? ""
        token = defaults.string(forKey: "token") ?? ""
        shareLoc = defaults.bool(forKey: "shareLoc")
        super.init()
        ws.delegate = self
        locManager.delegate = self
        locManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        wireSystemPTT()
        startPathMonitor()
    }

    func prepareAtLaunch() {
        PttSystemChannel.shared.prepare()
        if !token.isEmpty { connect() }
    }

    private func wireSystemPTT() {
        let sys = PttSystemChannel.shared
        sys.onTransmitBegin = { [weak self] in Task { @MainActor in self?.handleSystemTransmit(begin: true) } }
        sys.onTransmitEnd = { [weak self] in Task { @MainActor in self?.handleSystemTransmit(begin: false) } }
        sys.onAudioActivate = { [weak self] _ in
            Task { @MainActor in try? self?.audio.startEngine() }
        }
        sys.onAudioDeactivate = { [weak self] _ in
            Task { @MainActor in self?.audio.stopEngine() }
        }
        sys.onPushToken = { [weak self] data in
            Task { @MainActor in self?.notePushToken(data) }
        }
        sys.onIncomingSpeaker = { [weak self] speaker, _, payload in
            Task { @MainActor in
                self?.connect()
                if !speaker.isEmpty { self?.toast = "🔊 \(speaker) 正在說話" }
                _ = payload
            }
        }
        audio.onCapture = { [weak self] pcm in
            Task { @MainActor in self?.sendCaptured(pcm) }
        }
    }

    func login() async {
        loginError = nil
        let org = company.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if org.isEmpty {
            loginError = "請輸入公司代碼"
            return
        }
        company = org
        defaults.set(serverUrl, forKey: "serverUrl")
        defaults.set(company, forKey: "company")
        defaults.set(username, forKey: "username")
        do {
            let result = try await ApiClient.shared.login(baseUrl: serverUrl, username: username, password: pendingPassword, org: org)
            token = result.token
            defaults.set(token, forKey: "token")
            pendingPassword = ""
            connect()
        } catch {
            loginError = error.localizedDescription
        }
    }

    /// Temporary holder so the password is not stored in UserDefaults.
    var pendingPassword = ""

    func logout() {
        let t = token
        let url = serverUrl
        Task {
            await ApiClient.shared.deletePttPush(baseUrl: url, token: t)
            await ApiClient.shared.logout(baseUrl: url, token: t)
        }
        disconnect(clearAuth: true)
    }

    func connect() {
        authFailed = false
        guard !token.isEmpty, let url = ApiClient.wsURL(from: serverUrl) else {
            conn = "off"; connText = "請先登入"
            return
        }
        reconnectWork?.cancel()
        connectGen += 1
        conn = "wait"
        connText = retry > 0 ? "重新連線中…（第 \(retry) 次）" : "連線中…"
        EventLog.add("ws", retry > 0 ? "reconnect attempt \(retry)" : "connecting")
        ws.connect(url: url)
    }

    func disconnect(clearAuth: Bool = false) {
        reconnectWork?.cancel()
        pingTimer?.invalidate()
        internalStop(sendStop: true, viaSystem: false)
        if shareLoc { ws.send(json: ["t": "loc_off"]); stopLoc() }
        ws.close()
        audio.stopEngine()
        if clearAuth {
            token = ""
            defaults.removeObject(forKey: "token")
        }
        conn = "off"
        connText = clearAuth ? "已登出" : "未連線"
        talks = [:]
    }

    func join(channelId: String) { ws.send(json: ["t": "join", "channel": channelId]) }

    func openDm(peerId: String, peerName: String) {
        guard peerId != me?.id else { return }
        let ids = [me?.id ?? "", peerId].sorted()
        let conv = "dm:\(ids[0]):\(ids[1])"
        target = .dm(peerId: peerId, peerName: peerName, conv: conv)
        refreshPttLabel()
        loadMessagesFor(conv)
    }

    func backToChannel() {
        if let id = channelId, let ch = channels.first(where: { $0.id == id }) {
            target = .channel(id: ch.id, name: ch.name)
            loadMessagesFor(id)
        }
        refreshPttLabel()
    }

    func sendChat(_ text: String, conv convOpt: String? = nil) {
        guard let conv = convOpt ?? target?.conv else { toast = "請先選擇頻道"; return }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return }
        if t.count > 2000 { toast = "文字最多 2000 字"; return }
        if conn != "on" || !ws.isOpen {
            chatQueue.append((conv, t))
            EventLog.add("chat", "queued while offline n=\(chatQueue.count)")
            toast = "目前斷線，文字會在重連後自動送出（\(chatQueue.count)）"
            return
        }
        ws.send(json: ["t": "chat", "conv": conv, "text": t])
        EventLog.add("chat", "sent")
    }

    private func flushChatQueue() {
        guard !chatQueue.isEmpty else { return }
        let q = chatQueue
        chatQueue.removeAll()
        EventLog.add("chat", "flush \(q.count)")
        for (conv, text) in q { sendChat(text, conv: conv) }
    }

    func deleteAccount(password: String) async -> String? {
        do {
            try await ApiClient.shared.deleteAccount(baseUrl: serverUrl, token: token, password: password)
            await MainActor.run { self.disconnect(clearAuth: true) }
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func submitReport(text: String, screenshot: Data?) async -> String? {
        var shot = screenshot
        if let screenshot, screenshot.count > 300 * 1024 {
            shot = ApiClient.compressJPEG(screenshot, maxBytes: 300 * 1024)
        }
        let ver = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let meta: [String: Any] = [
            "platform": "ios",
            "device": UIDevice.current.model,
            "os": "iOS " + UIDevice.current.systemVersion,
            "appVersion": ver,
            "browser": "",
            "userAgent": "app.ptt.mobile"
        ]
        do {
            try await ApiClient.shared.submitReport(baseUrl: serverUrl, token: token, text: text, screenshot: shot, meta: meta, log: EventLog.dump())
            EventLog.add("report", "submitted")
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func forceReconnect(reason: String) {
        EventLog.add("net", reason)
        retry = 0
        if granted { toast = "偵測到網路切換，已安全結束發話並重新連線" }
        internalStop(sendStop: true, viaSystem: false)
        reconnectWork?.cancel()
        ws.close()
        connect()
    }

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let mon = NWPathMonitor()
        pathMonitor = mon
        mon.pathUpdateHandler = { [weak self] path in
            let kind: String
            if path.usesInterfaceType(.wifi) { kind = "wifi" }
            else if path.usesInterfaceType(.cellular) { kind = "cellular" }
            else if path.status == .satisfied { kind = "other" }
            else { kind = "none" }
            Task { @MainActor in
                guard let self else { return }
                EventLog.add("net", "path \(kind) status=\(path.status)")
                if !self.lastPath.isEmpty && self.lastPath != kind && kind != "none" {
                    self.forceReconnect("network \(self.lastPath)->\(kind)")
                }
                self.lastPath = kind
            }
        }
        mon.start(queue: DispatchQueue.global(qos: .utility))
    }

    func callDispatch() {
        guard me?.role == "user" else { toast = "調度員請從網頁調度台接聽來電"; return }
        guard conn == "on" else { toast = "尚未連線"; return }
        ws.send(json: ["t": "dispatch_call"])
    }

    func cancelDispatch() {
        guard let id = dispatchId else { return }
        ws.send(json: ["t": "dispatch_done", "id": id])
    }

    func uploadPhoto(_ data: Data) async {
        guard let conv = target?.conv else { toast = "請先選擇頻道"; return }
        guard let jpeg = ApiClient.compressJPEG(data) else {
            toast = "照片無法壓縮到 200KB 以內，請換一張較小的圖"
            return
        }
        do {
            _ = try await ApiClient.shared.uploadPhoto(baseUrl: serverUrl, token: token, conv: conv, jpeg: jpeg)
        } catch {
            toast = error.localizedDescription
        }
    }

    func loadMessagesFor(_ conv: String?) {
        guard let conv, !conv.isEmpty, !token.isEmpty else { return }
        let url = serverUrl
        let tok = token
        Task {
            do {
                let list = try await ApiClient.shared.listMessages(baseUrl: url, token: tok, conv: conv)
                await MainActor.run {
                    if self.target?.conv == conv { self.messages = list }
                }
            } catch { /* history is best-effort */ }
        }
    }

    /// In-app PTT button. On device this goes through PTChannelManager so lock-screen
    /// and Bluetooth accessories share the same path.
    func pttDown() {
        guard !pressing else { return }
        if useSystemPTT, PttSystemChannel.shared.joinedUUID != nil {
            PttSystemChannel.shared.requestBeginTransmit()
        } else {
            beginTalk()
        }
    }

    func pttUp() {
        if useSystemPTT, PttSystemChannel.shared.joinedUUID != nil {
            PttSystemChannel.shared.requestEndTransmit()
        } else {
            endTalk()
        }
    }

    private func handleSystemTransmit(begin: Bool) {
        if begin { beginTalk() } else { endTalk() }
    }

    private func beginTalk() {
        guard !pressing else { return }
        guard conn == "on" else { toast = "尚未連線"; return }
        guard let target else { toast = "請先選擇頻道"; return }
        if roleHere() == "listener" && ownAlert() == nil {
            toast = "你在這個頻道是「只聽」角色，不能發話"
            return
        }
        pending = []
        pendingConv = target.conv
        granted = false
        pressing = true
        pttMode = "requesting"
        pttLabel = "連線中…"
        try? audio.startEngine()
        switch target {
        case .dm(let peerId, _, _): ws.send(json: ["t": "ptt_start", "to": peerId])
        case .channel(let id, _): ws.send(json: ["t": "ptt_start", "conv": id])
        }
    }

    private func endTalk() {
        guard pressing else { return }
        internalStop(sendStop: true, viaSystem: false)
    }

    private func internalStop(sendStop: Bool, viaSystem: Bool) {
        if sendStop { ws.send(json: ["t": "ptt_stop"]) }
        pending = []
        pressing = false
        granted = false
        refreshPttLabel()
    }

    private func sendCaptured(_ pcm: Data) {
        if granted && ws.isOpen {
            ws.send(binary: pcm)
        } else if pressing && !granted {
            pending.append(pcm)
            if pending.count > 30 { pending.removeFirst() }
        }
    }

    func setShareLoc(_ on: Bool) {
        shareLoc = on
        defaults.set(on, forKey: "shareLoc")
        if on { startLoc() } else { stopLoc(); ws.send(json: ["t": "loc_off"]); locStatus = "未分享" }
    }

    func fireAlert() {
        guard conn == "on" else { toast = "尚未連線"; return }
        var body: [String: Any] = ["t": "alert_start"]
        if let last = lastFix {
            body["lat"] = last.coordinate.latitude
            body["lng"] = last.coordinate.longitude
            body["acc"] = last.horizontalAccuracy
        }
        ws.send(json: body)
    }

    func resolveAlert(id: String) { ws.send(json: ["t": "alert_resolve", "id": id]) }
    func requestLocs() { ws.send(json: ["t": "get_locs"]) }

    // MARK: WebSocket

    func wsDidOpen() {
        EventLog.add("ws", "open, hello+resume")
        var hello: [String: Any] = ["t": "hello", "token": token]
        if let ch = defaults.string(forKey: "channel"), !ch.isEmpty {
            hello["resume"] = ["channel": ch]
        }
        ws.send(json: hello)
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.ws.isOpen else { return }
                let ts = Int64(Date().timeIntervalSince1970 * 1000)
                self.ws.send(json: ["t": "ping", "ts": ts])
            }
        }
    }

    func wsDidReceive(json: [String: Any]) { handle(json) }

    func wsDidReceive(binary: Data) {
        guard binary.count >= 4 else { return }
        let sid = binary.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        _ = sid
        audio.play(pcm: binary.dropFirst(4))
    }

    func wsDidClose(code: Int, reason: String) {
        pingTimer?.invalidate()
        EventLog.add("ws", "close code=\(code) \(reason)")
        let wasGranted = granted
        if pressing || granted {
            internalStop(sendStop: false, viaSystem: false)
            toast = wasGranted ? "連線中斷，已安全結束發話（發言權已釋放）" : "連線中斷，發話尚未送出"
        }
        if authFailed || code == 4001 || code == 4003 {
            conn = "off"; connText = "請重新登入"; talks = [:]
            if authFailed { token = ""; defaults.removeObject(forKey: "token") }
            return
        }
        let base = min(10.0, pow(1.6, Double(retry)))
        let delay = base * (0.7 + Double.random(in: 0...0.6))
        retry += 1
        conn = "off"
        connText = "已斷線，\(Int(delay)) 秒後重連"
        talks = [:]
        let genAtClose = connectGen
        let work = DispatchWorkItem { [weak self] in Task { @MainActor in
            guard let self, self.connectGen == genAtClose else { return }
            self.connect()
        } }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func handle(_ m: [String: Any]) {
        let t = m["t"] as? String ?? ""
        switch t {
        case "pong":
            lastPong = Date()
            if let ts = m["ts"] as? Int64 {
                lastRtt = max(0, Int(Date().timeIntervalSince1970 * 1000) - Int(ts))
            } else if let ts = m["ts"] as? Int {
                lastRtt = max(0, Int(Date().timeIntervalSince1970 * 1000) - ts)
            }
            connText = connQualityText()
        case "auth_failed":
            authFailed = true
            token = ""
            defaults.removeObject(forKey: "token")
            internalStop(sendStop: false, viaSystem: false)
            ws.close()
            conn = "off"; connText = "請重新登入"
            let reason = m["reason"] as? String ?? ""
            if reason == "revoked" { toast = "你的帳號已被停用或密碼已重設，請重新登入" }
            else if reason == "org_inactive" { toast = "此公司已停用或方案到期，請重新登入" }
            else { toast = "登入已過期，請重新登入" }
        case "welcome":
            retry = 0
            if let u = m["user"] as? [String: Any] { me = JSONX.user(u) }
            channels = JSONX.arr(m, "channels").map(JSONX.channel)
            dms = JSONX.arr(m, "dms").map(JSONX.dm)
            online = JSONX.arr(m, "online").map(JSONX.online)
            alerts = JSONX.arr(m, "alerts").map(JSONX.alert)
            channels.forEach { if let lt = $0.lastTalk { lastTalks[$0.id] = lt } }
            EventLog.add("ws", "welcome resumed=\((m["resumedChannel"] as? String) ?? "")")
            conn = "on"; connText = connQualityText()
            let resumed = m["resumedChannel"] as? String
            let want = defaults.string(forKey: "channel") ?? resumed ?? "lobby"
            let ok = channels.first(where: { $0.id == want })?.id ?? channels.first?.id
            if let ok, ok != resumed { join(channelId: ok) }
            if shareLoc { startLoc() }
            refreshPttLabel()
            flushChatQueue()
        case "channels":
            channels = JSONX.arr(m, "channels").map(JSONX.channel)
            refreshPttLabel()
        case "joined":
            let chId = m["channel"] as? String
            if chId == nil || chId?.isEmpty == true {
                channelId = nil; target = nil; toast = "你已沒有任何頻道的權限"
                return
            }
            if (m["reason"] as? String) == "access_changed" { toast = "頻道權限已變更，已移回「大廳」" }
            channelId = chId
            defaults.set(chId, forKey: "channel")
            let name = channels.first(where: { $0.id == chId })?.name ?? chId!
            if case .dm = target {} else {
                target = .channel(id: chId!, name: name)
                loadMessagesFor(chId)
            }
            PttSystemChannel.shared.join(channelId: chId!, name: name)
            if let data = PttSystemChannel.shared.pushToken { notePushToken(data) }
            refreshPttLabel()
        case "members":
            if (m["channel"] as? String) == channelId { members = JSONX.arr(m, "members").map(JSONX.member) }
        case "online":
            online = JSONX.arr(m, "online").map(JSONX.online)
        case "floor_granted":
            let conv = m["conv"] as? String ?? ""
            if !pressing || conv != pendingConv { ws.send(json: ["t": "ptt_stop"]); return }
            granted = true
            pttMode = "talking"
            pttLabel = "說話中… 放開結束"
            let flush = pending; pending = []
            flush.forEach { ws.send(binary: $0) }
        case "floor_denied":
            let reason = m["reason"] as? String ?? ""
            let byName = m["byName"] as? String ?? ""
            internalStop(sendStop: false, viaSystem: false)
            if useSystemPTT { PttSystemChannel.shared.requestEndTransmit() }
            switch reason {
            case "busy":
                pttMode = "busy"; pttLabel = "\(byName.isEmpty ? "有人" : byName) 正在說話"
            case "offline": toast = "\(byName.isEmpty ? "對方" : byName) 目前不在線（不保存錄音，無法留言）"
            case "listen_only": toast = "你在這個頻道是「只聽」角色，不能發話"
            case "no_access": toast = "你沒有在這裡發話的權限"
            default: toast = "無法發話：\(reason)"
            }
        case "floor_revoked":
            let reason = m["reason"] as? String ?? ""
            let byName = m["byName"] as? String ?? ""
            internalStop(sendStop: false, viaSystem: false)
            if useSystemPTT { PttSystemChannel.shared.requestEndTransmit() }
            if reason == "preempted" { toast = "⚠️ 你的發話被 \(byName) 中斷（優先權較高）" }
            else if reason == "stalled" { toast = "網路不穩，語音中斷，已結束發話" }
            else { toast = "已達單次發話上限 60 秒" }
        case "talk_start":
            let conv = m["conv"] as? String ?? ""
            let info = TalkInfo(from: m["from"] as? String ?? "", fromName: m["fromName"] as? String ?? "",
                                streamId: m["streamId"] as? Int ?? 0, conv: conv)
            talks[conv] = info
            audio.resetPlayback()
            PttSystemChannel.shared.setRemoteSpeaker(name: info.fromName)
            try? audio.startEngine()
            if conv.hasPrefix("dm:"), target?.conv != conv { toast = "📞 \(info.fromName) 私人對講中" }
            refreshPttLabel()
        case "talk_end":
            let conv = m["conv"] as? String ?? ""
            let sid = m["streamId"] as? Int ?? -1
            if talks[conv]?.streamId == sid { talks.removeValue(forKey: conv) }
            if let ltObj = m["lastTalk"] as? [String: Any], let lt = JSONX.lastTalk(ltObj) {
                lastTalks[conv] = lt
            }
            if talks.isEmpty { PttSystemChannel.shared.setRemoteSpeaker(name: nil) }
            if conv == target?.conv { captionLine = ""; captionInterim = false }
            refreshPttLabel()
        case "channel_activity":
            if let id = m["channel"] as? String, let lt = JSONX.lastTalk(m["lastTalk"] as? [String: Any]) {
                lastTalks[id] = lt
            }
        case "loc":
            if let o = m["loc"] as? [String: Any] {
                let e = JSONX.loc(o)
                locs = (locs.filter { $0.id != e.id } + [e]).sorted { $0.ts > $1.ts }
            }
        case "locs":
            locs = JSONX.arr(m, "locs").map(JSONX.loc).sorted { $0.ts > $1.ts }
        case "loc_remove":
            if let id = m["id"] as? String { locs.removeAll { $0.id == id } }
        case "alert":
            if let o = m["alert"] as? [String: Any] {
                let a = JSONX.alert(o)
                alerts = alerts.filter { $0.id != a.id } + [a]
            }
        case "alert_end":
            if let id = m["id"] as? String { alerts.removeAll { $0.id == id } }
            toast = "✅ 警報已由 \(m["by"] as? String ?? "系統") 解除"
        case "error":
            toast = m["msg"] as? String ?? (m["code"] as? String)
        case "chat":
            if let obj = m["message"] as? [String: Any] {
                let msg = JSONX.chat(obj)
                if target?.conv == msg.conv {
                    messages = (messages.filter { $0.id != msg.id } + [msg]).sorted { $0.ts < $1.ts }
                }
            }
        case "caption":
            let conv = m["conv"] as? String ?? ""
            let name = m["fromName"] as? String ?? ""
            let text = m["text"] as? String ?? ""
            let interim = m["interim"] as? Bool ?? false
            let line = (name.isEmpty ? "" : "\(name)：") + text
            let relevant = conv == target?.conv || conv == channelId || talks[conv] != nil
            if relevant { captionLine = line; captionInterim = interim }
        case "queue_self":
            if let item = m["item"] as? [String: Any] {
                dispatchId = item["id"] as? String
                let status = item["status"] as? String ?? "waiting"
                dispatchStatus = status == "held" ? "調度台已接聽" : "正在等候調度台…"
                toast = dispatchStatus
            }
        case "dispatch_pickup":
            let conv = m["conv"] as? String ?? ""
            let by = m["by"] as? String ?? ""
            let byName = m["byName"] as? String ?? ""
            if let item = m["item"] as? [String: Any] { dispatchId = item["id"] as? String ?? dispatchId }
            dispatchStatus = "調度台已接聽"
            if !conv.isEmpty && !by.isEmpty && me?.role == "user" {
                target = .dm(peerId: by, peerName: byName.isEmpty ? "調度台" : byName, conv: conv)
                refreshPttLabel()
                loadMessagesFor(conv)
            }
            toast = "調度台已接聽"
        case "dispatch_transfer":
            let conv = m["conv"] as? String ?? ""
            let toName = m["toName"] as? String ?? ""
            let to = m["to"] as? String ?? ""
            if !conv.isEmpty && !to.isEmpty {
                target = .dm(peerId: to, peerName: toName.isEmpty ? "調度台" : toName, conv: conv)
                dispatchStatus = "已轉接給 \(toName)"
                refreshPttLabel()
                loadMessagesFor(conv)
            }
            toast = "通話已轉接給 \(toName.isEmpty ? "其他調度員" : toName)"
        case "dispatch_done":
            dispatchId = nil
            dispatchStatus = ""
            toast = "調度台通話已結束"
        default:
            break
        }
    }

    private func connQualityText() -> String {
        guard let rtt = lastRtt else { return "已連線" }
        let q: String
        if rtt < 80 { q = "訊號良好（\(rtt) ms）" }
        else if rtt < 200 { q = "訊號尚可（\(rtt) ms）" }
        else { q = "訊號較弱（\(rtt) ms）" }
        return "已連線 · " + q
    }

    var talkStatusText: String {
        talks.values.filter { $0.from != me?.id }.map { info in
            if info.conv.hasPrefix("dm:") { return "🔊 \(info.fromName) 正在私下對你說話" }
            let extra = info.conv != target?.conv ? "（\(channels.first(where: { $0.id == info.conv })?.name ?? "")）" : ""
            return "🔊 \(info.fromName) 正在說話\(extra)"
        }.joined(separator: "　")
    }

    private func refreshPttLabel() {
        if pressing && granted { pttMode = "talking"; pttLabel = "說話中… 放開結束"; return }
        if pressing { pttMode = "requesting"; pttLabel = "連線中…"; return }
        let listen = roleHere() == "listener" && ownAlert() == nil
        let busy = target.flatMap { talks[$0.conv] }.flatMap { $0.from == me?.id ? nil : $0 }
        if listen { pttMode = "listen"; pttLabel = "只聽（不能發話）" }
        else if let busy { pttMode = "busy"; pttLabel = "\(busy.fromName) 正在說話" }
        else { pttMode = "idle"; pttLabel = "按住講話" }
        if let conv = target?.conv, let last = lastTalks[conv] {
            let who = last.from == me?.id ? "我" : last.fromName
            lastTalkText = String(format: "最近說話：%@ · %.1f 秒", who, Double(last.durMs) / 1000)
        } else {
            lastTalkText = "這裡還沒有人說話"
        }
    }

    private func roleHere() -> String? {
        guard case .channel(let id, _) = target else { return "member" }
        return channels.first(where: { $0.id == id })?.myRole
    }

    private func ownAlert() -> AlertInfo? { alerts.first(where: { $0.userId == me?.id }) }

    private func notePushToken(_ data: Data) {
        let hex = data.map { String(format: "%02x", $0) }.joined()
        defaults.set(hex, forKey: "pttPushToken")
        let chId = channelId
        let uuid = chId.map { ChannelUUID.uuid(forChannelId: $0).uuidString }
        let url = serverUrl
        let tok = token
        Task {
            do {
                try await ApiClient.shared.registerPttPush(baseUrl: url, token: tok, pushToken: hex, channelId: chId, channelUUID: uuid)
                EventLog.add("apns", "registered ptt-push")
            } catch {
                EventLog.add("apns", "register failed")
            }
        }
    }

    func listCalls() async -> [[String: Any]] {
        (try? await ApiClient.shared.listCalls(baseUrl: serverUrl, token: token)) ?? []
    }

    func acceptInvite(codeOrUrl: String) async -> String? {
        guard let code = ApiClient.parseInviteCode(codeOrUrl) else { return "邀請碼格式不正確" }
        do {
            let ch = try await ApiClient.shared.acceptInvite(baseUrl: serverUrl, token: token, code: code)
            if let id = ch["id"] as? String { join(channelId: id) }
            toast = "已加入 " + ((ch["name"] as? String) ?? "頻道")
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func createInvite(channelId: String, kind: String, hours: Int?, uses: Int?) async -> (String?, [String: Any]?) {
        do {
            let inv = try await ApiClient.shared.createInvite(baseUrl: serverUrl, token: token, channelId: channelId, kind: kind, hours: hours, uses: uses)
            return (nil, inv)
        } catch {
            return (error.localizedDescription, nil)
        }
    }

    // MARK: Location + battery (native iOS can read battery, unlike Safari)

    private func startLoc() {
        locStatus = "定位中…"
        locManager.requestWhenInUseAuthorization()
        locManager.startUpdatingLocation()
        locTimer?.invalidate()
        locTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.sendLastFix() }
        }
    }

    private func stopLoc() {
        locManager.stopUpdatingLocation()
        locTimer?.invalidate()
        locTimer = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        lastFix = locations.last
        sendLastFix()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        locStatus = "無法取得定位：\(error.localizedDescription)"
    }

    private func sendLastFix() {
        guard let last = lastFix, ws.isOpen else { return }
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        let battery: Double? = level >= 0 ? Double(level) : nil
        let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        var body: [String: Any] = [
            "t": "loc",
            "lat": last.coordinate.latitude,
            "lng": last.coordinate.longitude,
            "acc": last.horizontalAccuracy,
            "charging": charging,
        ]
        if let battery { body["battery"] = battery }
        ws.send(json: body)
        let pct = battery.map { "\(Int(($0 * 100).rounded()))%" } ?? "不明"
        locStatus = "已回報 · 電量 \(pct)"
    }
}

