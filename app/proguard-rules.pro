# Keep JavaScriptInterface methods on MainActivity.AndroidBridge for WebView communication
-keepclassmembers class com.dailygames.hub.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.dailygames.hub.DailyRefreshWorker { *; }
