<img src="store/logo.svg" width="96" alt="Gymnotus logo">

# Gymnotus

[English](README.md) | 繁體中文

在手機上直接顯示各電源軌（power rail）功耗的 Android App：免 root、免接電腦。它透過 Android 15 的
PowerMonitor API 讀取裝置內建的電源監測器（ODPM）。

英文版 [README.md](README.md) 為主要版本；兩者有出入時以英文版為準。

## 顯示內容

- **即時**：實測 rail 的合計、從電源來源到子系統的流向圖、所選 rail 的趨勢圖，以及依子系統或電源來源分組的
  rail 清單。
- **儀表板**：每條 rail 一格，顯示目前功率、最近一分鐘的走勢與範圍；另有電池的電壓、電流與功率。
- **記錄**：把每筆讀數寫入你選定的 CSV 檔，切到其他 App 或關閉螢幕都會繼續；也可隨時匯出最近五分鐘。
- **詳細**：用來判斷量測本身是否可信的資訊：輪詢統計、系統總和與其組成 rail 的交叉檢查、原始讀數。
- **設定**（齒輪圖示）：更新模式、輪詢間隔、顯示選項、授權與隱私權文件。

介面有英文與繁體中文。

## 需求

- Android 15（API 35）以上，且裝置具備 ODPM rail。預期 Pixel 6 以後的機型都有；其他裝置會回傳空清單，
  App 會如實顯示。
- 目前只在一台裝置上測試過：Pixel 10 Pro（Android 17）。歡迎回報其他機型的結果。

## 建置與安裝

需要 JDK 17 以上與 Android SDK Platform 37。

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` 會產生精簡過的版本。若專案根目錄有 `keystore.properties`（不在 repo 內），並以
`storeFile`、`storePassword`、`keyAlias`、`keyPassword` 指定金鑰，就用你自己的金鑰簽章；沒有這個檔案時以
debug 金鑰簽章，足夠在裝置上試用。

## 更新頻率與選用權限

App 不需要任何權限即可使用，但系統此時最多每 20 秒才更新一次讀數。取得 `ACCESS_FINE_POWER_MONITORS`
後上限為 250 ms；在 Pixel 10 Pro 上觀察到讀數約每 0.5 秒變動一次。這個權限無法用一般的授權對話框取得，
只需授權一次，重開機後仍保留，方式有兩種：

- **不接電腦**：在設定頁點「設定快速模式」，到「開發人員選項 → 無線偵錯 → 使用配對碼配對裝置」，把六位數
  配對碼輸入 Gymnotus 的通知。App 會以 ADB 用戶端的身分連回手機自己的無線偵錯並替自己授權。需要開啟
  Wi-Fi；連線只在手機本機（loopback）。
- **接電腦**：

  ```sh
  adb shell pm grant io.github.tengigabytes.gymnotus android.permission.ACCESS_FINE_POWER_MONITORS
  ```

## 數字怎麼解讀

- 功率一律是能量差除以時間差，兩者都取自該 rail 自己的讀數。
- 沒有資料就顯示為缺值，絕不以 0 代替。
- 系統會對回傳的能量值加入小幅隨機雜訊，兩種模式皆然。
- 電池 rail 只計放電。使用電池時它接近整機功耗；外接電源時沒有整機總功率，App 會明說而不是顯示一個數字。
- 單一 rail 的電壓與電流 App 讀不到；只有電池同時有電壓、電流、功率。
- 螢幕關閉後手機會休眠、輪詢暫停，但能量不會漏：醒來後第一筆涵蓋整段休眠。螢幕關閉時預設每 5 秒輪詢一次。

## CSV 檔

以 `#` 開頭的行是中繼資料（裝置、監測器、統計），其餘是長格式表格：每次輪詢每條 rail 一列，包含原始
累計能量、該 rail 自己的時間戳記，以及電池與裝置狀態。缺值留空。pandas 可用
`pd.read_csv(path, comment="#")` 讀取。

## 裝置對照表

哪條 rail 屬於哪個電源來源、哪些 rail 加總成哪個系統總和，來自 [`data/device-maps`](data/device-maps)
內每款裝置一份的 JSON。沒有對照表時 App 仍可使用，改以 rail 名稱中的子系統分組。歡迎提供其他機型的
對照表，說明見該資料夾的 README。

## 隱私

所有資料都留在裝置上，見 [PRIVACY.md](PRIVACY.md)。

## 授權

Copyright © 2026 tengigabytes and Gymnotus contributors

Gymnotus 是自由軟體，以 GNU General Public License 第 3 版或（由你選擇）任何更新版本授權，全文見
[LICENSE](LICENSE)。使用到的第三方函式庫與資料來源列在 [THIRD_PARTY.md](THIRD_PARTY.md)。

## 命名由來

*Gymnotus* 是新熱帶區的弱電魚屬，靠感測自身電場的擾動來感知環境，正如本工具以不干擾的方式觀察電流的
去向。林奈於 1766 年最初將電鰻命名為 *Gymnotus electricus*，1864 年才移至 *Electrophorus* 屬。

這個字的原意是「裸背」，指沒有背鰭，與電無關。
