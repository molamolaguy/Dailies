# Keep JavaScriptInterface methods on HubActivity.AndroidBridge for WebView communication
-keepclassmembers class com.dailygames.hub.HubActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager's bundled consumer rules already keep ListenableWorker subclasses
# (DailyRefreshWorker, AutoBackupWorker, PagePreloadWorker, GameRecheckWorker).
