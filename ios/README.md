# iOS 原生客戶端（未在此環境編譯驗證）

此目錄是 **Xcode 工程原始碼**。Linux 建置環境沒有 Xcode、沒有 Apple 開發者簽章，因此 **iOS App 沒有在這裡編譯或安裝驗證**。請在 Mac 上用 Xcode 15+ 開啟並簽章。

## 需求

- macOS + Xcode 15 或更新（iOS 16 SDK，因使用 `PushToTalk` / `PTChannelManager`）
- 付費的 [Apple Developer Program](https://developer.apple.com/programs/)（每年費用由 Apple 收取）才能：
  - 開啟 **Push to Talk** capability（entitlement `com.apple.developer.push-to-talk`）
  - 設定 APNs（Push-to-Talk 推播的 topic 為 `<bundle id>.voip-ptt`）
  - 真機安裝與之後上架 App Store
- Bundle ID 預設：`app.ptt.mobile`（可改，但必須與伺服器 APNs topic 一致）

免費的個人開發簽章 **不能** 使用 Push to Talk entitlement。沒有此能力時，App 仍可在前景對講（走 WebSocket + 麥克風），但鎖屏／被系統殺掉後無法被 APNs 喚醒。

## 建置步驟

1. 用 Xcode 開啟 `PTTMobile.xcodeproj`（或安裝 [XcodeGen](https://github.com/yonaskolb/XcodeGen) 後在本目錄執行 `xcodegen generate`）。
2. Signing & Capabilities：
   - 選你的 Team
   - 確認有 **Push to Talk**
   - Background Modes 需包含 `audio` 與 `push-to-talk`（已寫在 `Info.plist`）
3. 連上 iPhone（iOS 16+），選該裝置，Run。
4. 第一次請允許麥克風與定位。加入頻道必須在前景、由使用者操作觸發（系統限制）。

模擬器可以編譯並測試登入／頻道 UI，但 PushToTalk 系統 UI、藍牙配件與 APNs 喚醒都需要真機。

## 與伺服器的關係

鎖屏收聽倚賴伺服器在別人開始講話時發送 **APNs `pushtotalk` 推播**。目前後端還沒做這段，規格寫在倉庫的 [`docs/SERVER_CHANGES.md`](../docs/SERVER_CHANGES.md)。沒有這項配合時：

- App 在前景或短暫背景：WebSocket 仍在就能收聽／發話
- App 被系統掛起或劃掉：收不到該次對講的開頭，也無法從鎖屏被喚醒

藍牙耳機／對講配件的實體鍵由系統 `PTChannelManager` 處理（`didBeginTransmittingFrom` / `didEndTransmittingFrom`），與畫面上的「按住講話」走同一條路。


## 登入

登入畫面的公司代碼為必填，預設 `DEMO`，請求體是 `{ "username", "password", "org" }`（不再送舊的 `company` 欄位）。文字／照片訊息、「呼叫調度台」與收到的即時字幕都已接上同一套協定。此環境沒有編譯 iOS。
