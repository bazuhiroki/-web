# kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class me.bazu.shitsuji.** {
    *** Companion;
}
-keepclasseswithmembers class me.bazu.shitsuji.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class me.bazu.shitsuji.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JS interface used by the WebView scraper is called reflectively
-keepclassmembers class me.bazu.shitsuji.tools.ScrapeBridge {
    public *;
}
