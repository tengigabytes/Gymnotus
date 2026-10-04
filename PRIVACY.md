# 隱私權政策 · Privacy Policy

最後更新 Last updated: 2026-10-04

## 中文

Gymnotus 只在你的裝置上處理資料。它沒有帳號、沒有廣告、沒有分析或當機回報元件，也不會把任何資料傳送到開發者或第三方的伺服器。

### App 讀取的資料

- 各電源軌（power rail）的累計能量，來源是 Android 的 PowerMonitor API。
- 電池狀態：電壓、電流、充電狀態、溫度、剩餘電量。
- 螢幕狀態、亮度設定、裝置溫度狀態。
- 裝置型號與系統版本（`Build` 資訊）。

這些資料用來在畫面上顯示功耗，並只保留在記憶體中（最近 5 分鐘）。

### App 儲存的資料

- **你匯出的 CSV 檔**：只有在你按下「匯出緩衝區」或「開始記錄」並自己選擇存檔位置時才會寫入。檔案內含上述讀數，以及裝置型號與系統版本指紋（build fingerprint）。要不要分享這些檔案由你決定。
- **設定**：輪詢間隔、檢視方式、圖表選取的項目，存在 App 的私有儲存空間。
- **ADB 金鑰**：若你使用「設定快速模式」，App 會產生一組金鑰，用來與你這台手機自己的「無線偵錯」配對。金鑰只存在 App 的私有儲存空間，不會離開裝置。

解除安裝 App 會刪除設定與金鑰；你匯出的 CSV 檔不受影響，需自行刪除。

### 權限與用途

| 權限 | 用途 |
|---|---|
| `ACCESS_FINE_POWER_MONITORS` | 選用。讓系統以約 0.5 秒而非 20 秒的間隔更新讀數。 |
| `INTERNET` | 僅用於連到這台手機本機（loopback）的無線偵錯服務，以完成「設定快速模式」。App 不會連線到任何遠端主機。 |
| `ACCESS_LOCAL_NETWORK` | 僅在「設定快速模式」時，用來找出這台手機自己的無線偵錯連接埠。 |
| `POST_NOTIFICATIONS` | 顯示記錄進行中的通知，以及輸入配對碼的通知。 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 讓你啟動的記錄在 App 不在畫面上時繼續。 |

### 聯絡方式

問題或疑慮請到 <https://github.com/tengigabytes/gymnotus/issues> 提出。

## English

Gymnotus processes data on your device only. It has no accounts, no ads, no analytics or crash-reporting
components, and it sends no data to the developer or to any third party.

### What the app reads

- Accumulated energy of each power rail, from Android's PowerMonitor API.
- Battery state: voltage, current, charging status, temperature, charge left.
- Screen state, brightness setting, device thermal status.
- Device model and system version (`Build` information).

This is used to show power on screen and is kept in memory only (the last five minutes).

### What the app stores

- **CSV files you export**: written only when you tap "Export buffer" or "Start log" and choose where to save.
  They contain the readings above, plus the device model and build fingerprint. Whether to share them is up to you.
- **Settings**: polling interval, views, and the items selected for the chart, in the app's private storage.
- **An ADB key**: if you use "Set up fast mode", the app generates a key pair to pair with this phone's own
  Wireless debugging. The key stays in the app's private storage and never leaves the device.

Uninstalling the app deletes the settings and the key. CSV files you exported are not affected and are yours to
delete.

### Permissions and what they are for

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_POWER_MONITORS` | Optional. Lets the system refresh readings about every 0.5 s instead of every 20 s. |
| `INTERNET` | Only to reach the Wireless debugging service on this phone itself (loopback) during "Set up fast mode". The app connects to no remote host. |
| `ACCESS_LOCAL_NETWORK` | Only during "Set up fast mode", to find this phone's own Wireless debugging ports. |
| `POST_NOTIFICATIONS` | The notification shown while logging, and the one that takes the pairing code. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps a log you started running while the app is not on screen. |

### Contact

Questions or concerns: <https://github.com/tengigabytes/gymnotus/issues>.
