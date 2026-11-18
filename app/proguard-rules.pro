-keepclassmembers class com.wmvaa.aigrader.NativeWebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes Signature, InnerClasses, EnclosingMethod

# Pertahankan class Activity dan ZXing Scanner agar tidak terhapus R8
-keep class com.wmvaa.aigrader.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
