import Foundation
import UIKit
import CoreLocation
import Combine

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
    @Published var toast: String?
    @Published var loginError: String?

    private let ws = WebSocketClient()
    private let audio = PcmAudio()
    private let defaults = UserDefaults.standard
    private var retry = 0
    private var authFailed = false
    private var lastPong = Date()
    private var pending: [Data] = []
    private var pendingConv = ""
    private var lastTalks: [String: LastTalk] = [:]
    private var pingTimer: Timer?
    private var reconnectWork: DispatchWorkItem?
    private let locManager = CLLocationManager()
    private var lastFix: CLLocation?
    private var locTimer: Timer?
    private var useSystemPTT = true

    override init() {
        serverUrl = defaults.string(forKey: "serverUrl") ?? "https://ptt-prototype.onrender.com"
        company = defaults.string(forKey: "company") ?? ""
        username = defaults.string(forKey: "username") ?? ""
        token = defaults.string(forKey: "token") ?? ""
        shareLoc = defaults.bool(forKey: "shareLoc")
        super.init()
        ws.delegate = self
        locManager.delegate = self
        locManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        wireSystemPTT()
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
        defaults.set(serverUrl, forKey: "serverUrl")
        defaults.set(company, forKey: "company")
        defaults.set(username, forKey: "username")
        do {
            let result = try await ApiClient.shared.login(baseUrl: serverUrl, username: username, password: pendingPassword, company: company.isEmpty ? nil : company)
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
        Task { await ApiClient.shared.logout(baseUrl: url, token: t) }
        disconnect(clearAuth: true)
    }

    func connect() {
        authFailed = false
        guard !token.isEmpty, let url = ApiClient.wsURL(from: serverUrl) else {
            conn = "off"; connText = "請先登入"
            return
        }
        reconnectWork?.cancel()
        conn = "wait"
        connText = retry > 0 ? "重新連線中…（第 \(retry) 次）" : "連線中…"
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
        target = .dm(peerId: peerId, peerName: peerName, conv: "dm:\(ids[0]):\(ids[1])")
        refreshPttLabel()
    }

    func backToChannel() {
        if let id = channelId, let ch = channels.first(where: { $0.id == id }) {
            target = .channel(id: ch.id, name: ch.name)
        }
        refreshPttLabel()
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
        retry = 0
        ws.send(json: ["t": "hello", "token": token])
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
        if pressing { internalStop(sendStop: false, viaSystem: false) }
        if authFailed || code == 4001 || code == 4003 {
            conn = "off"; connText = "請重新登入"; talks = [:]
            if authFailed { token = ""; defaults.removeObject(forKey: "token") }
            return
        }
        let delay = min(10.0, pow(1.6, Double(retry)))
        retry += 1
        conn = "off"
        connText = "已斷線，\(Int(delay)) 秒後重連"
        talks = [:]
        let work = DispatchWorkItem { [weak self] in Task { @MainActor in self?.connect() } }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func handle(_ m: [String: Any]) {
        let t = m["t"] as? String ?? ""
        switch t {
        case "pong": lastPong = Date()
        case "auth_failed":
            authFailed = true
            token = ""
            defaults.removeObject(forKey: "token")
            internalStop(sendStop: false, viaSystem: false)
            ws.close()
            conn = "off"; connText = "請重新登入"
            toast = (m["reason"] as? String) == "revoked"
                ? "你的帳號已被停用或密碼已重設，請重新登入" : "登入已過期，請重新登入"
        case "welcome":
            retry = 0
            if let u = m["user"] as? [String: Any] { me = JSONX.user(u) }
            channels = JSONX.arr(m, "channels").map(JSONX.channel)
            dms = JSONX.arr(m, "dms").map(JSONX.dm)
            online = JSONX.arr(m, "online").map(JSONX.online)
            alerts = JSONX.arr(m, "alerts").map(JSONX.alert)
            channels.forEach { if let lt = $0.lastTalk { lastTalks[$0.id] = lt } }
            conn = "on"; connText = "已連線"
            let want = defaults.string(forKey: "channel") ?? "lobby"
            let ok = channels.first(where: { $0.id == want })?.id ?? channels.first?.id
            if let ok { join(channelId: ok) }
            if shareLoc { startLoc() }
            refreshPttLabel()
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
            if case .dm = target {} else { target = .channel(id: chId!, name: name) }
            PttSystemChannel.shared.join(channelId: chId!, name: name)
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
            break
        default:
            break
        }
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
        // Hex-encode and keep in memory. Once the server implements the API in
        // docs/SERVER_CHANGES.md this should POST /api/me/ptt-push.
        let hex = data.map { String(format: "%02x", $0) }.joined()
        defaults.set(hex, forKey: "pttPushToken")
        print("[PTT] ephemeral push token (\(data.count) bytes) — server registration not implemented yet")
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

