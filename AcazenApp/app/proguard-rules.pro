# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep WebView JavaScript interfaces (if any are added later), since R8
# can otherwise strip methods that are only called from JS.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Kotlin metadata so reflection-based libraries keep working.
-keep class kotlin.Metadata { *; }

# Lottie ships its own consumer-rules.pro, but keep this as a safety net
# in case shrinkResources/minify ever strips something it depends on
# (JSON parsing, animatable value classes) via reflection.
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**
