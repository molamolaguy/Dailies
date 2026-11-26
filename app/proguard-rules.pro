# Keep HubActivity.AndroidBridge and its JavascriptInterface methods/annotations under R8 minification
-keepattributes JavascriptInterface,*Annotation*
-keep class com.dailygames.hub.HubActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager's bundled consumer rules already keep ListenableWorker subclasses
# (DailyRefreshWorker, AutoBackupWorker, PagePreloadWorker, GameRecheckWorker).
