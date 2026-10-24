# Add project specific ProGuard rules here.
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,*Annotation*
# Preserve the local privacy-safe crash frame filter after shrinking.
-keeppackagenames com.example.**
-keepnames class com.example.core.diagnostics.**
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
#-renamesourcefileattribute SourceFile
