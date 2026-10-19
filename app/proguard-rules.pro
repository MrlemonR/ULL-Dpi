# WebView köprüsü: JS'ten isimle çağrılıyor, küçültülmemeli.
-keepclassmembers class com.ull.dpi.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
