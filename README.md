# Gymnotus

Android ODPM power rail monitor：在手機上（免 root、免接電腦）即時顯示各電源軌（power rails）的功耗。

目前狀態：**Phase 0 驗證原型**。只有一個畫面，用來確認 Android 15 的 PowerMonitor API
在實機上的行為（有哪些 rail、更新粒度、是否需要權限）。

## 需求

- 裝置：Android 15（API 35）以上。預期在 Pixel 6 以後機型（Tensor）才有 ODPM rail；
  其他裝置 API 會回傳空清單，App 會顯示「no power monitors」。
- 建置：JDK 17 以上、Android SDK Platform 37。

## 建置與安裝

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

或手機以 USB 偵錯連線後直接 `.\gradlew.bat installDebug`。

## Phase 0 畫面說明

- 上方：裝置資訊、monitor 數量、輪詢次數／錯誤數、API 呼叫延遲（min/med/max）。
- 輪詢間隔：100 / 250 / 500 / 1000 ms。切換間隔或按 Reset 會清空統計與匯出緩衝區。
- 每條 rail：
  - 功率（mW）＝ ΔE / Δt，ΔE、Δt 都取自該 rail 自己的讀數；`(stale)` 表示時間戳記未更新、沿用前一筆。
  - `E`：原始累積能量（µJ）；`t`：該 rail 的時間戳記（elapsedRealtime）；`age`：收到回呼時讀數的年齡。
  - `Δt n / min/med/max`：實際更新間隔（相鄰兩筆不同時間戳記之差）的分布。
  - `repeat`：時間戳記與前一筆相同（未更新）的次數與比例。
- Export CSV：匯出最近 5 分鐘的原始讀數（每次輪詢另附 BatteryManager 的電流、電壓、充電狀態與電量計數器）。檔案開頭以 `#` 起始的行是中繼資料
  （裝置、rail 清單與類型、各 rail 統計），其後為長格式表格（每次輪詢每條 rail 一列）。
  缺值一律留空，不以 0 代替。pandas 可用 `pd.read_csv(path, comment="#")` 讀取。

- 時間軸圖表：在 `By subsystem`／`By source` 檢視中，點任一群組或 rail 就會加入圖表（最多 8 條，再點一次移除），
  可切換最近 1 分鐘或 5 分鐘；點或拖曳圖表可讀出某一時刻各條線的數值。
- Start log：選擇存檔位置後，每次輪詢即時寫入 CSV；切到其他 App 或關螢幕都會繼續，通知列可停止。
  LOG 不使用 wake lock：手機休眠時輪詢暫停，rail 的累計能量不會漏，醒來後第一筆涵蓋整段休眠。
  螢幕關閉時預設改為每 5 秒輪詢一次，以免輪詢本身墊高待機功耗（可在畫面上關閉）。

沒有 LOG 在進行時，App 只在畫面可見時輪詢；前景時保持螢幕常亮。資料只在本機處理。

## 更新粒度與選用權限

不需任何權限即可使用，但系統（PowerStatsService）此時最多每 20 秒才更新一次讀數。
授予 `ACCESS_FINE_POWER_MONITORS` 後上限為 250 ms（Pixel 10 Pro／Android 17 實測約 500 ms 更新一次）。
這個權限無法用一般的執行時授權對話框取得，但只需授權一次（重開機後仍保留），有兩種方式：

- 不接電腦：在 Gymnotus 按 `Set up fast mode`，到「開發人員選項 → 無線偵錯」點「使用配對碼配對裝置」，
  再把六位數配對碼輸入 Gymnotus 的通知。App 會以 ADB 用戶端的身分連回手機自己的無線偵錯並替自己授權
  （需要連上 Wi-Fi；連線只走手機本機的 loopback）。
- 接電腦，用 adb：

```powershell
adb shell pm grant io.github.tengigabytes.gymnotus android.permission.ACCESS_FINE_POWER_MONITORS
```

畫面上方會顯示目前是哪一種模式。系統會對回傳的能量值加入小幅隨機雜訊，兩種模式皆然。

## 授權

Copyright © 2026 tengigabytes and Gymnotus contributors

Gymnotus 以 GNU General Public License 第 3 版或（由你選擇）任何更新版本授權，全文見 [LICENSE](LICENSE)。
使用到的第三方函式庫與資料來源列在 [THIRD_PARTY.md](THIRD_PARTY.md)；隱私權政策見 [PRIVACY.md](PRIVACY.md)。

## 命名由來

*Gymnotus* 是新熱帶區的弱電魚屬，以感測自身電場的擾動來感知環境（electrolocation），
象徵本工具以低侵入方式觀察電流在裝置中的分布。林奈於 1766 年最初也將電鰻命名為
*Gymnotus electricus*，後於 1864 年才移至 *Electrophorus* 屬。

字源上 *Gymnotus* 意為「裸背」（指沒有背鰭），與電無關。
