# Keep JavaScriptInterface methods on HubActivity.AndroidBridge for WebView communication
-keepclassmembers class com.dailygames.hub.HubActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.dailygames.hub.DailyRefreshWorker { *; }
