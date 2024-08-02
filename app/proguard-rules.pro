# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# ---- gson specific start ----

# TODO: we dont really use generics here
-keepattributes Signature

# we allow shrink or deletion, because unused members have no meaning here
-keepclassmembers ,allowshrinking,allowoptimization class io.github.sagimor6.woltbillsplitter.** {
    !transient !static <fields>;
}

# ---- gson specific end ----

# for easier debugging
-keep ,allowshrinking,allowoptimization class io.github.sagimor6.woltbillsplitter.** {
    *;
}
