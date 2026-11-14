# ProEQ Hi-Fi 本地音樂播放器 (Android + GitHub Actions 雲端自動編譯)

這是一個專為耳機發燒友設計的 Android 本地無損音樂播放與 **32-bit 浮點 10 頻段專業等化器 (DSP)** 專案，支援完全不需在本機安裝 Android Studio，直接透過 **GitHub Actions** 雲端免費編譯出可安裝的 `.apk` 檔案。

---

## 核心功能與音響工程設計

1. **Jetpack Media3 (ExoPlayer) 本地無損播放核心**
   - 支援掃描手機內建儲存空間中的 `FLAC`、`WAV`、`ALAC`、`AAC/M4A`、`OGG/OPUS`、`MP3` 等音訊格式。
   - 顯示音訊封裝格式標籤、演出者、專輯與精準時間軸控制。
   - 支援拔除有線耳機或 USB 小尾巴 (Audio Becoming Noisy) 時自動暫停保護。

2. **Android 原生 `DynamicsProcessing` 32-Bit 浮點 DSP 等化器**
   - **Pre-amp 前級數位衰減/增益 (-15.0 dB ~ +15.0 dB)**：內建 **Auto 防爆音 (Auto Headroom)** 模式，當拉高特定頻段時自動對稱下調輸入前級增益，徹底避免常見軟體 EQ 因超出 `0 dBFS` 而產生的破音與數位削波 (Digital Clipping)。
   - **ISO 標準 10 頻段精細控制**：涵蓋 `31Hz`、`62Hz`、`125Hz`、`250Hz`、`500Hz`、`1kHz`、`2kHz`、`4kHz`、`8kHz`、`16kHz`，步進精度達 `0.5 dB`。
   - **-0.5 dBFS 硬體峰值限幅器 (Peak Limiter)**：10:1 壓縮比、1ms 超快啟動時間，保護高靈敏度耳機與平板單體。
   - **即時頻率響應曲線繪製 (Real-Time Spline Graph)**：在 UI 上即時預覽包含 Pre-amp 補償後的總和頻率響應曲線。

3. **耳機特性補償預設集 (Target Presets)**
   - **Flat (Bit-Perfect 原音直通)**：全頻段 0dB 直出。
   - **Planar Harman (平板耳機極低頻補償)**：針對開放式平板耳機補償 31Hz-125Hz 極低頻下潛，並柔化 4kHz 齒音區。
   - **Closed Woodie Warmth (封閉式木碗收斂)**：收斂 125Hz-250Hz 封閉式腔體駐波感，提升中高頻通透度。
   - **Vocal Intimate (人聲貼耳毒音)**、**Classical & Soundstage (交響大編制音場)**、**V-Shape Dynamic**。

---

## 如何使用 GitHub Actions 雲端自動編譯 APK（免裝 Android Studio）

本專案已設定好 `gradle/actions/setup-gradle@v4` 自動化工作流，**不需要手動準備 `gradle-wrapper.jar`**，只要上傳文字原始碼即可編譯：

### 步驟 1：建立 GitHub 儲存庫並上傳檔案
1. 登入 GitHub，點擊右上角 **`+`** -> **`New repository`**。
2. 輸入儲存庫名稱（例如 `ProEQ-Android-Player`），選擇 **Public**（公開儲存庫可享免費無限制的 GitHub Actions 雲端編譯分鐘數），點擊 **Create repository**。
3. 將本壓縮包解壓縮後的所有檔案與資料夾（包含 `.github/workflows/android-build.yml`、`app/`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`）上傳並 Commit 到 `main` 分支：
   - **網頁版上傳**：點擊頁面上的 `uploading an existing file`，將解壓縮後的所有資料夾與檔案拖曳進去（**請確認 `.github` 隱藏資料夾也有一併拖入**），點擊 **Commit changes**。
   - **或使用 Git 指令上傳**：
     ```bash
     git init
     git add .
     git commit -m "Initial commit: ProEQ Android Music Player"
     git branch -M main
     git remote add origin https://github.com/<你的帳號>/<你的儲存庫名稱>.git
     git push -u origin main
     ```

### 步驟 2：等待雲端編譯並下載 APK
1. 進入 GitHub 儲存庫頂部的 **`Actions`** 分頁。
2. 你會看到 **`Android Cloud Build (ProEQ Music Player)`** 正在自動執行（或點擊左側該工作流名稱，再點右側 **`Run workflow`** 手動觸發）。
3. 等待約 **2 ~ 3 分鐘** 顯示綠色勾勾 ✅ 後，點進該次執行紀錄。
4. 滑到頁面最底部的 **`Artifacts`** 區塊，點擊下載 **`ProEQ-MusicPlayer-APK`**。
5. 解壓縮下載的檔案，即可獲得已簽章、可直接安裝在手機上的 `app-release.apk` 與 `app-debug.apk`！
