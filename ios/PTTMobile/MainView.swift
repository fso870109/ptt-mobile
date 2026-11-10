import SwiftUI
import PhotosUI
import UIKit
import AVFoundation

struct MainView: View {
    @EnvironmentObject var engine: PttEngine
    @State private var tab = 0
    @State private var sosHolding = false
    @State private var sosTask: Task<Void, Never>?
    @State private var alertToShow: AlertInfo?
    @State private var chatText = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var showReport = false
    @State private var showSettings = false
    @State private var showMicWhy = false
    @State private var showLocWhy = false
    @State private var showPhotoWhy = false
    @State private var showPhotoPicker = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Circle().fill(engine.conn == "on" ? Color.green : engine.conn == "wait" ? Color.orange : Color.gray)
                    .frame(width: 10, height: 10)
                Text(engine.connText).font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Text((engine.me?.displayName ?? "") + (engine.me?.role == "admin" ? "（管理員）" : ""))
                    .font(.footnote)
                Button("設定") { showSettings = true }.font(.footnote)
                Button("登出") { engine.logout() }.font(.footnote)
            }.padding(.horizontal).padding(.top, 8)

            if !engine.alerts.isEmpty {
                Button {
                    alertToShow = engine.alerts.first
                } label: {
                    Text("🚨 " + engine.alerts.map {
                        let who = $0.userId == engine.me?.id ? "你" : $0.name
                        return who + " 發出緊急警報" + ($0.channelName.map { "（\($0)）" } ?? "")
                    }.joined(separator: "；"))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(8)
                    .background(Color.red)
                }
            }

            Picker("", selection: $tab) {
                Text("頻道").tag(0)
                Text("成員").tag(1)
                Text("私訊").tag(2)
                Text("訊息").tag(3)
                Text("位置").tag(4)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .onChange(of: tab) { newTab in
                if newTab == 4 { engine.requestLocs() }
                if newTab == 3 { engine.loadMessagesFor(engine.target?.conv) }
            }

            TabView(selection: $tab) {
                channelList.tag(0)
                memberList.tag(1)
                dmList.tag(2)
                chatList.tag(3)
                locList.tag(4)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            HStack {
                Text(engine.target?.label ?? "尚未加入頻道").font(.headline)
                Spacer()
                if case .dm = engine.target {
                    Button("← 回到頻道") { engine.backToChannel() }
                }
            }.padding(.horizontal)

            Text(engine.talkStatusText).foregroundStyle(.orange).frame(minHeight: 20)

            if !engine.captionLine.isEmpty {
                Text((engine.captionInterim ? "字幕（辨識中）：" : "字幕：") + engine.captionLine)
                    .font(.subheadline)
                    .foregroundStyle(.blue)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.vertical, 6)
                    .background(Color(.secondarySystemBackground))
            }

            PttButton(label: engine.pttLabel, mode: engine.pttMode,
                      onDown: { requestTalk() }, onUp: { engine.pttUp() })
                .padding(.vertical, 8)

            Text(engine.lastTalkText).font(.footnote).foregroundStyle(.secondary)

            if engine.me?.role == "user" {
                Button {
                    if engine.dispatchId != nil && engine.dispatchStatus.contains("等候") {
                        engine.cancelDispatch()
                    } else {
                        engine.callDispatch()
                    }
                } label: {
                    Text(dispatchLabel)
                        .frame(maxWidth: .infinity)
                        .padding(10)
                }
                .padding(.horizontal)
            }

            Text(sosHolding ? "繼續按住… 放開取消" : "🚨 長按 2 秒發出緊急警報")
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(12)
                .background(Color.red)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .padding()
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { _ in
                            guard !sosHolding else { return }
                            sosHolding = true
                            sosTask?.cancel()
                            sosTask = Task {
                                try? await Task.sleep(nanoseconds: 2_000_000_000)
                                if !Task.isCancelled {
                                    await MainActor.run {
                                        engine.fireAlert()
                                        sosHolding = false
                                    }
                                }
                            }
                        }
                        .onEnded { _ in
                            if sosHolding {
                                sosTask?.cancel()
                                sosHolding = false
                            }
                        }
                )
        }
        .alert("提示", isPresented: Binding(
            get: { engine.toast != nil },
            set: { if !$0 { engine.toast = nil } }
        )) {
            Button("好") { engine.toast = nil }
        } message: {
            Text(engine.toast ?? "")
        }
        .sheet(isPresented: $showReport) { ReportSheet().environmentObject(engine) }
        .sheet(isPresented: $showSettings) { SettingsSheet().environmentObject(engine) }
        .sheet(item: $alertToShow) { a in
            AlertSheet(alert: a)
        }
        .alert("需要使用麥克風", isPresented: $showMicWhy) {
            Button("取消", role: .cancel) {}
            Button("繼續") { engine.pttDown() }
        } message: {
            Text("按住講話時會使用麥克風，把你的聲音即時傳給頻道或私訊對象。語音只即時傳送，不會被錄音保存。")
        }
        .alert("分享目前位置", isPresented: $showLocWhy) {
            Button("取消", role: .cancel) {}
            Button("開啟") { engine.setShareLoc(true) }
        } message: {
            Text("開啟後，會把你目前的位置與電量分享給同頻道成員及公司管理員／調度員。系統只保留最新一筆，不建立移動軌跡。你可以隨時關閉。")
        }
        .alert("選擇照片", isPresented: $showPhotoWhy) {
            Button("取消", role: .cancel) {}
            Button("繼續") { showPhotoPicker = true }
        } message: {
            Text("接下來會開啟相簿，讓你選擇要傳送到這個對話的照片。我們不會讀取你相簿中的其他內容，照片最多保存 7 天。")
        }
        .onChange(of: engine.alerts) { new in alertToShow = new.first }
    }

    private func requestTalk() {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .undetermined:
            showMicWhy = true
        default:
            engine.pttDown()
        }
    }

    private var dispatchLabel: String {
        if engine.dispatchStatus.contains("等候") { return engine.dispatchStatus + "（點此取消）" }
        if !engine.dispatchStatus.isEmpty { return engine.dispatchStatus }
        return "呼叫調度台"
    }

    private var chatList: some View {
        VStack(spacing: 0) {
            List {
                if engine.messages.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("這個對話還沒有文字或照片")
                        Text("在下方輸入後按「送出」，或點「照片」上傳（約 200KB 以內）")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    ForEach(engine.messages) { m in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(m.from == engine.me?.id ? "我" : m.fromName) · \(kindLabel(m.kind))")
                                .font(.caption).foregroundStyle(.secondary)
                            if let text = m.text, !text.isEmpty { Text(text) }
                            if m.kind == "photo", let pid = m.photoId {
                                RemotePhoto(photoId: pid)
                            }
                        }
                    }
                }
            }
            HStack {
                TextField("輸入文字訊息", text: $chatText)
                    .textFieldStyle(.roundedBorder)
                Button("送出") {
                    engine.sendChat(chatText)
                    chatText = ""
                }
                Button("照片") { showPhotoWhy = true }
            }
            .padding(8)
            .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
            .onChange(of: photoItem) { item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        await engine.uploadPhoto(data)
                    }
                    await MainActor.run { photoItem = nil }
                }
            }
        }
    }

    private func kindLabel(_ kind: String) -> String {
        switch kind {
        case "photo": return "照片"
        case "caption": return "字幕"
        default: return "文字"
        }
    }

    private var channelList: some View {
        List(engine.channels) { c in
            Button {
                if c.id == engine.channelId { engine.backToChannel() }
                else { engine.join(channelId: c.id) }
            } label: {
                VStack(alignment: .leading) {
                    Text("# \(c.name)  \(c.myRoleLabel)").foregroundStyle(.primary)
                    Text("\(c.online) 人在線").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var memberList: some View {
        List {
            Section("目前頻道") {
                ForEach(engine.members) { m in
                    Button {
                        if m.id != engine.me?.id { engine.openDm(peerId: m.id, peerName: m.name) }
                    } label: {
                        HStack {
                            Text("🟢 \(m.name)\(m.id == engine.me?.id ? "（你）" : "")")
                            Spacer()
                            if m.id != engine.me?.id { Text("私人對講 ›").foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            Section("其他線上的人") {
                let ids = Set(engine.members.map(\.id))
                let others = engine.online.filter { $0.id != engine.me?.id && !ids.contains($0.id) }
                ForEach(others) { u in
                    Button { engine.openDm(peerId: u.id, peerName: u.name) } label: {
                        HStack { Text("🟢 \(u.name)"); Spacer(); Text("私人對講 ›").foregroundStyle(.secondary) }
                    }
                }
            }
        }
    }

    private var dmList: some View {
        List(engine.dms) { d in
            Button { engine.openDm(peerId: d.peerId, peerName: d.peerName) } label: {
                Text("🔒 \(d.peerName)")
            }
        }
    }

    private var locList: some View {
        List {
            Toggle("分享我的位置與電量", isOn: Binding(
                get: { engine.shareLoc },
                set: { on in
                    if on { showLocWhy = true } else { engine.setShareLoc(false) }
                }
            ))
            Text(engine.locStatus).font(.caption).foregroundStyle(.secondary)
            ForEach(engine.locs) { l in
                let bat = l.battery.map { "電量 \($0)%" + ((l.charging ?? false) ? "⚡" : "") } ?? "電量不明"
                VStack(alignment: .leading) {
                    Text("📍 \(l.name)\(l.id == engine.me?.id ? "（你）" : "") · \(bat)")
                    Text("\(l.channelName ?? "") · \(l.online ? "在線" : "離線")")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct PttButton: View {
    let label: String
    let mode: String
    let onDown: () -> Void
    let onUp: () -> Void
    @State private var down = false

    var color: Color {
        switch mode {
        case "talking": return .red
        case "busy", "requesting": return .orange
        case "listen": return .gray
        default: return .green
        }
    }

    var body: some View {
        Text(label)
            .font(.title3.bold())
            .foregroundStyle(.white)
            .frame(width: 168, height: 168)
            .background(color)
            .clipShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        if !down { down = true; onDown() }
                    }
                    .onEnded { _ in
                        if down { down = false; onUp() }
                    }
            )
    }
}

struct SettingsSheet: View {
    @EnvironmentObject var engine: PttEngine
    @Environment(\.dismiss) private var dismiss
    @AppStorage("colorScheme") private var colorSchemePref = "system"
    @State private var showReport = false
    @State private var showDelete = false
    @State private var showCalls = false
    @State private var showScanWhy = false
    @State private var showScanner = false
    @State private var showInvite = false
    @State private var showCodeAlert = false
    @State private var inviteCode = ""
    @State private var password = ""
    @State private var err = ""
    @State private var deleting = false

    var body: some View {
        NavigationStack {
            List {
                Button("回報問題") { showReport = true }
                Button("通話紀錄") { showCalls = true }
                Button("掃描／輸入邀請碼") { showScanWhy = true }
                if canInvite {
                    Button("產生頻道邀請") { showInvite = true }
                }
                Picker("外觀", selection: $colorSchemePref) {
                    Text("跟隨系統").tag("system")
                    Text("淺色").tag("light")
                    Text("深色").tag("dark")
                }
                Button("隱私權政策") { open("/privacy") }
                Button("服務條款") { open("/terms") }
                Button("刪除帳號說明") { open("/delete-account") }
                Button("刪除我的帳號") { showDelete = true }
                    .foregroundStyle(.red)
            }
            .navigationTitle("設定")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("關閉") { dismiss() } } }
            .sheet(isPresented: $showReport) { ReportSheet().environmentObject(engine) }
            .sheet(isPresented: $showCalls) { CallLogSheet().environmentObject(engine) }
            .sheet(isPresented: $showScanner) {
                QRScannerView { code in
                    showScanner = false
                    Task { _ = await engine.acceptInvite(codeOrUrl: code) }
                }
            }
            .sheet(isPresented: $showInvite) { InviteCreateSheet().environmentObject(engine) }
            .alert("掃描邀請 QR Code", isPresented: $showScanWhy) {
                Button("取消", role: .cancel) {}
                Button("開啟相機") { showScanner = true }
                Button("手動輸入") { showCodeAlert = true }
            } message: {
                Text("接下來會使用相機掃描頻道邀請 QR Code，讓你加入對講頻道。我們不會拍照或上傳畫面。")
            }
            .alert("輸入邀請碼", isPresented: $showCodeAlert) {
                TextField("邀請碼或連結", text: $inviteCode)
                Button("取消", role: .cancel) {}
                Button("加入") { Task { _ = await engine.acceptInvite(codeOrUrl: inviteCode) } }
            }
            .alert("刪除我的帳號", isPresented: $showDelete) {
                SecureField("輸入目前密碼以確認", text: $password)
                Button("取消", role: .cancel) { password = ""; err = "" }
                Button("確認刪除", role: .destructive) { Task { await doDelete() } }
            } message: {
                Text("此動作無法復原。我們會刪除你的帳號與個人資料，並將你送出的文字訊息匿名化。語音本來就不會保存。若你是公司最後一位管理員，系統會拒絕刪除。" + (err.isEmpty ? "" : "\n\n" + err))
            }
        }
    }

    private var canInvite: Bool {
        if engine.me?.role == "admin" || engine.me?.role == "org_admin" { return true }
        return engine.channels.contains { $0.myRole == "supervisor" }
    }

    private func open(_ path: String) {
        var root = engine.serverUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while root.hasSuffix("/") { root.removeLast() }
        if let url = URL(string: root + path) {
            UIApplication.shared.open(url)
        }
    }

    private func doDelete() async {
        let pw = password
        password = ""
        guard !pw.isEmpty else { err = "請輸入密碼"; showDelete = true; return }
        deleting = true
        let msg = await engine.deleteAccount(password: pw)
        deleting = false
        if let msg {
            err = msg
            showDelete = true
        } else {
            dismiss()
        }
    }
}

struct CallLogSheet: View {
    @EnvironmentObject var engine: PttEngine
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [[String: Any]] = []

    var body: some View {
        NavigationStack {
            List {
                if rows.isEmpty {
                    Text("目前沒有通話紀錄。系統只記錄說話者、頻道或私訊對象、開始時間、長度與是否被插話，不存音訊，保留 90 天。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, c in
                    Text(fmt(c))
                }
            }
            .navigationTitle("通話紀錄")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("關閉") { dismiss() } } }
            .task { rows = await engine.listCalls() }
        }
    }

    private func fmt(_ c: [String: Any]) -> String {
        let name = c["fromName"] as? String ?? "使用者"
        let kind = c["kind"] as? String ?? ""
        let whereText = kind == "dm" ? ("私訊 · " + (c["peerName"] as? String ?? "對方")) : ("#" + (c["channelName"] as? String ?? ""))
        let dur = Double(c["durMs"] as? Int ?? Int(c["durMs"] as? Double ?? 0)) / 1000
        let ts = (c["startedAt"] as? Double) ?? Double(c["startedAt"] as? Int ?? 0)
        let when = Date(timeIntervalSince1970: ts / 1000).formatted(date: .abbreviated, time: .shortened)
        let pre = (c["preempted"] as? Bool) == true ? " · 被插話" : ""
        return "\(name) → \(whereText) · \(when) · \(String(format: "%.1f", dur)) 秒\(pre)"
    }
}

struct InviteCreateSheet: View {
    @EnvironmentObject var engine: PttEngine
    @Environment(\.dismiss) private var dismiss
    @State private var channelId = ""
    @State private var kind = "join"
    @State private var hours = ""
    @State private var uses = ""
    @State private var url = ""
    @State private var err = ""

    var channels: [Channel] {
        if engine.me?.role == "admin" || engine.me?.role == "org_admin" { return engine.channels }
        return engine.channels.filter { $0.myRole == "supervisor" }
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("頻道", selection: $channelId) {
                    ForEach(channels) { c in Text("# " + c.name).tag(c.id) }
                }
                Picker("類型", selection: $kind) {
                    Text("加入頻道（已有帳號）").tag("join")
                    Text("邀請註冊（新人開帳號）").tag("register")
                }
                TextField("有效時數（空白＝不限）", text: $hours).keyboardType(.numberPad)
                TextField("次數上限（空白＝不限）", text: $uses).keyboardType(.numberPad)
                if !url.isEmpty {
                    ShareLink("分享邀請連結", item: url)
                    Text(url).font(.footnote).textSelection(.enabled)
                }
                if !err.isEmpty { Text(err).foregroundStyle(.red) }
            }
            .navigationTitle("產生邀請")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("關閉") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("產生") { Task { await create() } }
                }
            }
            .onAppear { if channelId.isEmpty { channelId = channels.first?.id ?? "" } }
        }
    }

    func create() async {
        err = ""
        let h = Int(hours)
        let u = Int(uses)
        let (msg, inv) = await engine.createInvite(channelId: channelId, kind: kind, hours: h, uses: u)
        if let msg { err = msg; return }
        url = inv?["url"] as? String ?? ""
    }
}

final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String) -> Void)?
    private let session = AVCaptureSession()
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.bounds
        preview.videoGravity = .resizeAspectFill
        preview.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.layer.addSublayer(preview)
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let s = obj.stringValue else { return }
        session.stopRunning()
        onFound?(s)
    }
}

struct QRScannerView: UIViewControllerRepresentable {
    var onFound: (String) -> Void
    func makeUIViewController(context: Context) -> ScannerVC {
        let vc = ScannerVC()
        vc.onFound = onFound
        return vc
    }
    func updateUIViewController(_ uiViewController: ScannerVC, context: Context) {}
}

struct ReportSheet: View {
    @EnvironmentObject var engine: PttEngine
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var showShotWhy = false
    @State private var showShotPicker = false
    @State private var shot: Data?
    @State private var err = ""
    @State private var sending = false

    var body: some View {
        NavigationStack {
            Form {
                Section("問題說明") {
                    TextEditor(text: $text).frame(minHeight: 120)
                    Text("系統會自動附上裝置、系統、App 版本與最近連線事件（不含 token、密碼或語音）。").font(.footnote).foregroundStyle(.secondary)
                }
                Section("截圖（選填）") {
                    Button("選擇截圖") { showShotWhy = true }
                    if shot != nil { Text("已選擇截圖").font(.footnote) }
                }
                if !err.isEmpty { Text(err).foregroundStyle(.red) }
            }
            .navigationTitle("回報問題")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("送出") { Task { await send() } }.disabled(sending || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .photosPicker(isPresented: $showShotPicker, selection: $photoItem, matching: .images)
            .alert("附上截圖", isPresented: $showShotWhy) {
                Button("取消", role: .cancel) {}
                Button("繼續") { showShotPicker = true }
            } message: {
                Text("接下來會開啟相簿，讓你選擇要附在問題回報裡的截圖。我們不會讀取你相簿中的其他內容。")
            }
            .onChange(of: photoItem) { item in
                Task {
                    if let item, let data = try? await item.loadTransferable(type: Data.self) {
                        shot = ApiClient.compressJPEG(data, maxBytes: 300 * 1024) ?? data
                    }
                }
            }
        }
    }

    func send() async {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { err = "請填寫問題說明"; return }
        sending = true
        let msg = await engine.submitReport(text: body, screenshot: shot)
        sending = false
        if let msg { err = msg } else { dismiss() }
    }
}

struct AlertSheet: View {
    @EnvironmentObject var engine: PttEngine
    let alert: AlertInfo

    var body: some View {
        let mine = alert.userId == engine.me?.id
        VStack(alignment: .leading, spacing: 12) {
            Text(mine ? "你已發出緊急警報" : "\(alert.name) 發出緊急警報").font(.title2.bold())
            Text(whereText)
            if mine { Text("警報期間你擁有最高發話權").foregroundStyle(.secondary) }
            if let lat = alert.lat, let lng = alert.lng,
               let url = URL(string: "https://www.openstreetmap.org/?mlat=\(lat)&mlon=\(lng)#map=17/\(lat)/\(lng)") {
                Link("打開地圖", destination: url)
            }
            if engine.me?.role == "admin" || mine {
                Button(mine ? "取消我的警報" : "解除警報") { engine.resolveAlert(id: alert.id) }
                    .buttonStyle(.borderedProminent)
            }
            Spacer()
        }.padding()
    }

    var whereText: String {
        var s = ""
        if let n = alert.channelName { s += "頻道：\(n)　" }
        if let lat = alert.lat, let lng = alert.lng {
            s += String(format: "位置：%.5f, %.5f", lat, lng)
            if let acc = alert.acc { s += "（±\(acc)m）" }
        } else {
            s += "位置：不明（未取得定位）"
        }
        return s
    }
}

struct RemotePhoto: View {
    @EnvironmentObject var engine: PttEngine
    let photoId: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 180)
            } else {
                ProgressView().frame(height: 80)
            }
        }
        .task {
            image = await PhotoCache.shared.image(baseUrl: engine.serverUrl, token: engine.token, photoId: photoId)
        }
    }
}
