import SwiftUI

struct LoginView: View {
    @EnvironmentObject var engine: PttEngine
    @State private var password = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text("📻").font(.system(size: 56))
                Text("一鍵對講").font(.largeTitle.bold())
                Text("原生 App · iOS 16+ PushToTalk").foregroundStyle(.secondary)
                TextField("伺服器網址", text: $engine.serverUrl)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12).background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 10))
                TextField("公司代碼（必填，預設 DEMO）", text: $engine.company)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .padding(12).background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 10))
                TextField("帳號", text: $engine.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12).background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 10))
                SecureField("密碼", text: $password)
                    .padding(12).background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 10))
                Button("登入") {
                    engine.pendingPassword = password
                    Task { await engine.login() }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                if let err = engine.loginError, !err.isEmpty {
                    Text(err).foregroundStyle(.red)
                }
                Text("帳號由管理員在網頁後台建立。登入必須填公司代碼（預設 DEMO）。iOS 背景／鎖屏對講需要 Apple 的 PushToTalk 能力與伺服器發送 APNs（見 docs/SERVER_CHANGES.md）。")
                    .font(.footnote).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(24)
        }
    }
}
