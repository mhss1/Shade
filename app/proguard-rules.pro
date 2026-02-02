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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-keep class org.tensorflow.** { *; }
-keep class com.google.auto.value.** { *; }
-keep class com.google.** { *; }
-keepclasseswithmembers class org.tensorflow.** {
    public *;
    private *;
    protected *;
}
-keepclasseswithmembers class com.google.** {
    public *;
    private *;
    protected *;
}
-keepclassmembers class org.tensorflow.** {
    public *;
    private *;
    protected *;
}
-keepclassmembers class com.google.** {
    public *;
    private *;
    protected *;
}

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue

# Remove all Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}