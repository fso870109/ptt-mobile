import SwiftUI

struct MainView: View {
    @EnvironmentObject var engine: PttEngine
    @State private var tab = 0
    @State private var sosHolding = false
    @State private var sosTask: Task<Void, Never>?
    @State private var alertToShow: AlertInfo?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Circle().fill(engine.conn == "on" ? Color.green : engine.conn == "wait" ? Color.orange : Color.gray)
                    .frame(width: 10, height: 10)
                Text(engine.connText).font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Text((engine.me?.displayName ?? "") + (engine.me?.role == "admin" ? "（管理員）" : ""))
                    .font(.footnote)
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
                Text("位置").tag(3)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .onChange(of: tab) { newTab in if newTab == 3 { engine.requestLocs() } }

            TabView(selection: $tab) {
                channelList.tag(0)
                memberList.tag(1)
                dmList.tag(2)
                locList.tag(3)
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

            PttButton(label: engine.pttLabel, mode: engine.pttMode,
                      onDown: { engine.pttDown() }, onUp: { engine.pttUp() })
                .padding(.vertical, 8)

            Text(engine.lastTalkText).font(.footnote).foregroundStyle(.secondary)

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
        .sheet(item: $alertToShow) { a in
            AlertSheet(alert: a)
        }
        .onChange(of: engine.alerts) { new in alertToShow = new.first }
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
                set: { engine.setShareLoc($0) }
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
