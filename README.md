# Geelo 吉洛 · 原生手機 App

Geelo（中文名：吉洛）的原生 Push-to-Talk 客戶端（iOS + Android）。伺服器是既有的網頁應用：

- 服務：<https://ptt-prototype.onrender.com>
- 協定：原型倉庫的 `docs/API.md`（PCM 16 kHz、WebSocket 即時轉發、不保存錄音）
- 這個倉庫**不修改**伺服器程式碼

網頁版在手機鎖屏時收不到對講。這個 App 的重點就是補上那一塊：背景／鎖屏收聽與發話，以及藍牙耳機、實體對講鍵。

## 為什麼用原生，而不是 React Native / Expo

| 方案 | 為什麼沒選 |
|---|---|
| Expo Go / 託管工作流程 | 不能用 Apple `PushToTalk`（`PTChannelManager`），也不能正確宣告 Android 14 的 `foregroundServiceType=microphone` |
| React Native + 自訂原生模組 | 背景音訊、PTT framework、各家對講手機的實體鍵最後還是要寫 Kotlin／Swift；雙層架構對第一版沒有好處 |
| **Kotlin（Android）+ Swift（iOS）** | 直接使用系統 API，這是最有機會在鎖屏真正聽到、按得下去的作法 |

兩個平台共用同一套協定，沒有共用語系執行時（避免再加一套跨平台執行環境）。

## 功能

- 登入（可填伺服器網址；**公司代碼必填**，欄位名送 `org`，預設填 `DEMO`）
- 頻道列表、切換、成員、一對一私人對講
- 按住講話；誰在講、忙碌、被更高優先權中斷
- 角色：主管可搶話、只聽不能講（警報期間除外）
- 位置與電量回報（原生可讀電量，**包含 iOS**；網頁版的 Safari 做不到）
- 緊急警報（長按 2 秒）
- 文字訊息顯示與傳送；照片顯示與上傳（壓縮至約 200KB JPEG）
- 「呼叫調度台」（一般使用者；等候中可取消）
- 顯示收到的即時字幕（`caption`）。此版不在講電話時做裝置端語音辨識（Android `SpeechRecognizer` 會與 `AudioRecord` 搶麥克風）
- 登入錯誤會顯示 `need_org`／`invalid_credentials`／`disabled`／`org_disabled`／`org_expired`／`seat_limit`／`org_inactive` 的中文說明
- **Android**：前景服務常駐通知、WebSocket 重連、`AudioRecord` / `AudioTrack` 16 kHz PCM、媒體鍵與常見對講手機 PTT 廣播
- **iOS**：Apple PushToTalk framework（iOS 16+）、系統鎖屏／Dynamic Island 對講鈕、藍牙配件走同一條 `PTChannelManager` 路徑

## 目錄

```
android/     Kotlin App（可在 Linux 編譯 debug APK）
ios/         Swift App（需 Mac + Xcode，見 ios/README.md）
docs/        SERVER_CHANGES.md（伺服器要補的 PTT 推播規格）
tools/       protocol-test.js（協定層自動測試）
scripts/     build-android.sh
```

## Android：建置與安裝

### 在本機建置

需要 JDK 17 與 Android SDK（compileSdk / targetSdk 34，minSdk 26）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
cd android
./gradlew assembleDebug
# 產出：android/app/build/outputs/apk/debug/app-debug.apk
```

或執行 `scripts/build-android.sh`。

### 安裝到手機

1. 手機開啟「允許安裝未知來源的應用程式」（或用 `adb install`）。
2. 把 APK 傳到手機後開啟安裝，或：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

3. 開啟 Geelo，伺服器預設是 `https://ptt-prototype.onrender.com`。
4. 公司代碼預設為 `DEMO`（必填）。用管理員在 [網頁後台](https://ptt-prototype.onrender.com/admin) 建立的帳號登入。舊版 APK 送的是 `company` 欄位，現在伺服器會回 `400 need_org`，請改用 0.2.0 之後的版本（本版為 0.3.0）。
5. 請允許**麥克風**與**通知**（沒有麥克風權限就無法以麥克風類型啟動前景服務）。定位可稍後再允許。
6. 登入後通知列會留下常駐通知，此時可以把 App 送到背景或鎖屏：應仍能聽到對講。通知上的「發話」是點一下開始／再點結束（通知列不好做「按住」）。藍牙耳機的播放／暫停或線控、以及部分三防機的 PTT 實體鍵則是按下開始、放開結束。

免費 Render 主機閒置約 15 分鐘會睡死，第一次連線可能要等 30–60 秒。

### Android 14 背景麥克風限制

系統不允許 App 在背景**新啟動** `microphone` 前景服務。這個 App 會在你打開並授權之後就啟動並保持通知。如果系統後來把服務殺掉，請再打開 App 一次。不要依賴開機自啟（開機時也還在背景，一樣啟動不了麥克風服務）。

## iOS：未編譯驗證

Linux 上沒有 Xcode，也沒有 Apple 簽章，**iOS 工程沒有在這裡編譯或跑過**。請看 [`ios/README.md`](ios/README.md)。

重點：

- 需要 macOS、Xcode 15+、iPhone（iOS 16+）
- 鎖屏系統對講鈕與被殺掉後的喚醒，需要付費的 Apple Developer 帳號，並開啟 Push to Talk capability
- 伺服器還要會發 `apns-push-type: pushtotalk`（規格在 [`docs/SERVER_CHANGES.md`](docs/SERVER_CHANGES.md)）。**在後端補上之前，iPhone 只在 App 前景或短暫背景、WebSocket 仍活著時收得到對講。**

## 協定測試

```bash
cd tools
npm install    # 安裝 ws；若旁邊剛好有 ptt-prototype/node_modules/ws 也會自動找到
node protocol-test.js
```

腳本會：

1. 打雲端 `/api/health`，並驗證登入格式：缺少 `org`、或只送舊的 `company` 欄位會得到 `400 need_org`；帶 `org`／`orgCode` 與假帳號會得到 `401 invalid_credentials`。若本機有管理員憑證檔，會再用 `org=DEMO` 登入管理員、建立 `t_` 開頭暫用帳號驗證成功登入，測完刪除（腳本不會印出密碼）。
2. 在本機用環境變數啟動一份 `ptt-prototype`（寫入 `/tmp`，**不修改**原型倉庫的檔案），完整測試 `org=DEMO` 登入、加入頻道、發話鎖、搶話、PCM 封包（4 位元組 big-endian `streamId` + s16le）、文字訊息、即時字幕、照片上傳、以及 `dispatch_call`／`queue_self`／`dispatch_done`。

沒有在此環境啟動 Android 模擬器（映像檔很大，而且這個 Linux 環境通常沒有可用的 KVM／圖形加速）。協定層用腳本對真實伺服器驗證；APK 有實際編譯。

## 限制（請當真相看）

- 不保存錄音，錯過的語音無法回放。
- iOS 背景喚醒尚未有伺服器推播實作（見上）。
- 各家對講手機的 PTT 鍵沒有單一標準。已處理媒體鍵、`HEADSETHOOK`，以及 Kyocera / Sonim / 常見 `PTT_BUTTON*` 廣播；無法保證每一款工業手機都能用，實際機種需要真機再調。
- Android 通知列的「發話」是切換，不是按住。
- 尚未上架，這是 debug 簽名的 APK，不能直接提交 Play 商店。
- 位置列表用系統地圖／OpenStreetMap 連結開啟，App 內沒有內建地圖圖磚。

## 你要自己辦的帳號（上架與 iOS 真機）

這些都需要你本人申請，這個環境不會代你註冊任何付費帳號：

1. **Apple Developer Program**（付費）— iOS 真機 PushToTalk、APNs、之後上 App Store。在開發者後台建立 App ID（Bundle ID `app.ptt.mobile`）、開啟 Push to Talk、建立 APNs `.p8` 金鑰交給伺服器。
2. **Google Play Console**（付費）— 若要上架 Android。上架前還要用你的 upload key 簽一份 release AAB/APK（目前提供的是 debug APK，只供內測安裝）。
3. 既有的 GitHub、Render 帳號已在用，不需要為了跑這個 App 再付一筆新的。

## 授權與隱私

語音只在傳輸當下經過伺服器，伺服器不存錄音。位置與電量只在使用者打開分享時回報，伺服器只留最新一筆、不寫軌跡。登入 token 存在系統的私有設定裡。
