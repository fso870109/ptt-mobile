# 伺服器需要配合的改動（原生 App）

本文件是給 `ptt-prototype` 後端的規格。**原生 App 倉庫不會改伺服器。**  
目前的 REST + WebSocket 協定（見原型的 `docs/API.md`）已經夠前景對講、發話鎖、位置、警報使用。  
要讓 **iPhone 在鎖屏、被系統掛起、甚至從背景被喚醒後仍能收聽**，還需要 Apple Push-to-Talk 推播。

> 多公司「公司代碼」欄位：客戶端登入已預留 JSON 欄位 `company`（空白則不送）。伺服器實作多租戶後再開始解讀即可；現有伺服器忽略未知欄位，行為不變。

---

## 1. 為什麼網頁協定不夠

- 伺服器只把語音轉給**當下已連上 WebSocket** 的人，而且不存錄音。晚連上來的人會錯過該次發話開頭。
- iOS 不會讓普通 WebSocket 在背景一直活著。App 被掛起後，必須靠 APNs 把行程叫醒，再重連 `/ws`、`hello`、`join`，才能收到後續的 PCM。
- 伺服器在 `hello` / `join` 時，如果該 conv 已經有人在講，會補一則 `talk_start`（這點現有實作已具備）。重連後可以接上**剩下的**音訊，但開頭那 1–2 秒通常已經過去。

Android 走前景服務（`microphone|mediaPlayback`），一般**不需要**推播就能鎖屏收聽／發話。Android 14 起不能在背景「新啟動」麥克風前景服務，所以 App 必須在使用者打開並授權麥克風之後就常駐通知。服務若在背景被殺掉，使用者需再打開 App 一次。

---

## 2. 註冊 iOS Push-to-Talk token

App 在建立 `PTChannelManager` 後會收到短暫有效的 APNs token：

```swift
func channelManager(_ channelManager: PTChannelManager, receivedEphemeralPushToken pushToken: Data)
```

- Token 只在使用者**已加入系統 PTT 頻道**時有效。
- 離開頻道、換裝置、重裝系統後都會變，**不要當成長期裝置 ID 快取**。
- 每次收到新 token，或使用者 `join` 到不同頻道時，都要重新註冊。

建議新增：

### `POST /api/me/ptt-push`

`Authorization: Bearer <token>`

```json
{
  "platform": "ios",
  "kind": "voip-ptt",
  "token": "<hex 或 base64url，二選一，請在實作時固定一種並更新 API.md>",
  "bundleId": "app.ptt.mobile",
  "channelId": "lobby",
  "channelUUID": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
}
```

| 欄位 | 說明 |
|---|---|
| `platform` | 目前只需 `ios` |
| `kind` | `voip-ptt`（Apple Push-to-Talk 短暫 token，不是一般 alert token，也不是普通 VoIP PushKit token） |
| `token` | `receivedEphemeralPushToken` 的位元組 |
| `bundleId` | 預設 `app.ptt.mobile`。APNs topic = `{bundleId}.voip-ptt` |
| `channelId` | 我們伺服器裡的頻道 id（大廳為 `lobby`）。一對一可先不註冊，或另外用 `conv` |
| `channelUUID` | 系統 PTT 頻道 UUID。客戶端用 UUID v5 產生，算法見 §5 |

成功：`200 { "ok": true }`  
使用者登出、被踢、或送 `channelId: null` 時請刪除該使用者的 token。

也可同時支援 WebSocket 訊息（二選一即可）：

```json
{ "t": "ptt_push_token", "platform": "ios", "kind": "voip-ptt", "token": "...", "bundleId": "app.ptt.mobile", "channelId": "lobby", "channelUUID": "..." }
```

儲存建議：只放記憶體或短 TTL 資料表（`userId, channelId, token, bundleId, updatedAt`）。同一使用者同一頻道只保留最新一筆。

---

## 3. 何時發送 APNs

在現有 `ptt_start` 成功、即將（或剛剛）`broadcastConv(..., talk_start)` 的同一條路徑上，對該 `conv` 的每個接收者：

1. 不要發給正在講話的人。
2. 查出該使用者為此 conv／頻道註冊的 `voip-ptt` token（若有）。
3. 對每個 token 發一則 APNs（見 §4）。
4. **不要**在 `talk_end` 再發一則「沒有 `activeSpeaker`」的推播。Apple 範例裡若 payload 沒有 `activeSpeaker`，`incomingPushResult` 只能回 `.leaveChannel`，會讓系統退出 PTT 頻道。講完後由已喚醒的 App 透過 WebSocket 的 `talk_end` 呼叫 `setActiveRemoteParticipant(nil)` 即可。

一對一（`conv` 為 `dm:a:b`）：對對方的 token 發送，`activeSpeaker` 仍是發話者顯示名，並帶上 `conv`。

### 可選優化

若該使用者此刻已有活著的 WebSocket，可以不發推播（省 APNs、也避免前景 App 被重複叫醒）。為了實作單純，全發也可以。

有一種名為 `service-update` 的 PTT 推播會被系統嚴苛限流（約每 10–15 分鐘一則高優先），**不要**用它來通知「有人開始講話」。講話通知請用下面這種帶 `activeSpeaker` 的一般 PTT 推播。

---

## 4. APNs 請求規格

正式環境：`https://api.push.apple.com/3/device/<token>`  
沙盒：`https://api.sandbox.push.apple.com/3/device/<token>`

HTTP/2 POST，標頭：

| 標頭 | 值 |
|---|---|
| `authorization` | `bearer <由 .p8 金鑰簽出的 JWT>` |
| `apns-push-type` | `pushtotalk` |
| `apns-topic` | `{bundleId}.voip-ptt`（例如 `app.ptt.mobile.voip-ptt`） |
| `apns-priority` | `10` |
| `apns-expiration` | `0`（過期不補送，避免使用者聽到過時的「有人在講」） |

JSON body 建議：

```json
{
  "activeSpeaker": "小明",
  "conv": "lobby",
  "from": "u0abc123",
  "fromName": "小明",
  "streamId": 12
}
```

`activeSpeaker` 是系統 UI 要顯示的名字，建議與 `fromName` 相同。客戶端的實作：

```swift
func incomingPushResult(...) -> PTPushResult {
    guard let speaker = pushPayload["activeSpeaker"] as? String, !speaker.isEmpty else {
        return .activeRemoteParticipant(PTParticipant(name: "對講", image: nil))
        // 不要在講話結束時回 .leaveChannel
    }
    return .activeRemoteParticipant(PTParticipant(name: speaker, image: nil))
}
```

App 被喚醒後應立刻重連 `/ws` → `hello` → 必要時 `join`，然後播放後續 binary。

APNs 回 `410 Unregistered` 時刪除該 token。

---

## 5. 頻道 UUID 算法（與 iOS 客戶端一致）

iOS 檔案 `ChannelUUID.uuid(forChannelId:)`：

- UUID v5
- Namespace：DNS `6ba7b810-9dad-11d1-80b4-00c04fd430c8`
- Name：`app.ptt.mobile.channel.` + 頻道 id  
  例：大廳 → `app.ptt.mobile.channel.lobby`

伺服器**發送推播時不必帶這個 UUID**（系統靠 token 對應到已加入的頻道）。若要除錯或寫管理後台，可用同一算法核對 App 上報的 `channelUUID`。

Apple 限制：同一時間系統只能加入 **一個** PTT 頻道。這與目前伺服器「每個使用者只在一個頻道」的模型一致。切換頻道時 App 會離開舊的系統頻道並加入新的（必須在前景、由使用者觸發）。

---

## 6. 伺服器環境變數（建議）

| 變數 | 說明 |
|---|---|
| `APNS_TEAM_ID` | Apple Developer Team ID |
| `APNS_KEY_ID` | 金鑰 ID |
| `APNS_P8` 或 `APNS_P8_PATH` | Auth Key `.p8` 內容或路徑 |
| `APNS_BUNDLE_ID` | 預設 `app.ptt.mobile` |
| `APNS_PRODUCTION` | `1` 用正式 APNs，否則沙盒 |

`.p8` 在 [Apple Developer](https://developer.apple.com/account/resources/authkeys/list) 建立「Apple Push Notification service SSL (Sandbox & Production)」金鑰。這需要付費的 Apple Developer 帳號；請由 App 擁有者自行申請，不要把金鑰提交到 git。

JWT 使用 ES256，標準 APNs token 算法即可（`iss` = Team ID，`kid` = Key ID）。

---

## 7. 客戶端暫時的行為（伺服器尚未實作時）

- iOS 會把 token 留在本機（`UserDefaults` 鍵 `pttPushToken`），並在 log 印出長度，但**不會**呼叫尚不存在的 API。
- 前景與短暫背景：只靠 WebSocket，與網頁版相同。
- Android：前景服務，不依賴本文件的 API。

實作完 §2–§4 後，請更新原型的 `docs/API.md`，原生 App 再補上 `POST /api/me/ptt-push` 的呼叫即可。
