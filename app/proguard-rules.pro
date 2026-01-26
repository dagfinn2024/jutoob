# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# 2. Aggressive R8 / ProGuard configuration (Logging kept for debugging)
#-assumenosideeffects class android.util.Log {
#    public static *** d(...);
#    public static *** v(...);
#    public static *** i(...);
#    public static *** e(...);
#}

# GeckoView / WebExtension (GeckoView requires some classes to be kept for JNI and reflection)
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.geckoview.WebExtension** { *; }

# Remove unused annotations
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Preserve the line number information for debugging stack traces (optional, but useful)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
