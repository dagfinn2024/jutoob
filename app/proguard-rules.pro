# ProGuard rules for JuToob

# 1. NewPipeExtractor & Rhino (JavaScript engine used by extractor)
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn com.grack.nanojson.**
-dontwarn org.jsoup.**
-dontwarn org.json.**

# Rhino / Mozilla JavaScript engine missing classes on Android
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn java.lang.instrument.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**

# 2. OkHttp3 / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 3. GeckoView
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.geckoview.WebExtension** { *; }
-dontwarn org.mozilla.geckoview.**

# 4. Common Missing Classes
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# 5. Core Library Desugaring
-dontwarn java.nio.file.**
-dontwarn java.nio.channels.**

# 7. TAndroidLame (LAME MP3 encoder JNI)
-keep class com.naman14.androidlame.** { *; }
-dontwarn com.naman14.androidlame.**

# 6. General R8 settings
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
