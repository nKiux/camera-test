# M 相機

Camera2 實作的手動相機：手動對焦、快門時間、自動／手動 ISO、曝光補償。
不做 HDR、景深、夜景等任何影像演算法；拍出的是感光元件經 JPEG 編碼的結果。

## 取得 APK

方法 A：GitHub Actions（不用裝任何開發工具）
1. 在 GitHub 建立新儲存庫，把本資料夾的內容全部上傳（含 .github 資料夾），分支名為 main。
2. 開啟儲存庫的 Actions 分頁，等待 build-apk 跑完（約 3 到 5 分鐘）。
3. 點進該次執行，在 Artifacts 下載 m-camera-apk，解壓得到 app-debug.apk。

方法 B：命令列
1. 安裝 JDK 17 與 Android SDK，並設定環境變數 ANDROID_HOME（或在專案根目錄建立 local.properties，內容 sdk.dir=SDK 路徑）。
2. 在專案根目錄執行 ./gradlew assembleDebug（Windows 用 gradlew.bat assembleDebug）。

方法 C：Android Studio
1. File > Open 選擇本資料夾，等待 Gradle 同步。
2. Build > Build Bundle(s) / APK(s) > Build APK(s)。
   APK 位於 app/build/outputs/apk/debug/app-debug.apk。

安裝：把 APK 傳到手機，允許「安裝未知應用程式」後點開即可。
需求：Android 10 以上，且鏡頭支援手動感光元件控制（多數中高階機種有）。
照片存在 Pictures/MCamera。
