# Add project specific ProGuard rules here.
-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,*Annotation*
# WebRTC JNI resolves Java names/callbacks. Verified by a native DTLS loopback
# test on both debug and minified release, not by disabling R8.
-keep class org.webrtc.** { *; }
# Keep the concrete, JNI-invoked override adapter across minified releases.
# Without these explicit methods, older Android desugaring may attempt an
# Observer$-CC companion that is not present in the release APK (CI #412).
-keep class com.example.core.calls.EmptyPeerObserver { *; }
# The release instrumentation APK invokes our retained production EGL entry
# point rather than independently linking an incompatible static-interface
# desugaring bridge for EglBase.create() on minSdk 23.
-keep class com.example.core.calls.WebRtcEglFactory { *; }
# WebRTC M150 JNI_OnLoad also resolves org.jni_zero.JniZero by its original name.
-keep class org.jni_zero.** { *; }
# The vendor WebRTC M150 AAR references a code-generated bridge that it does not
# package. The JNI runtime registers native methods from its .so; only this
# missing, non-instantiated class is optional. Keep all real JNI Zero classes.
# CI must still execute actual native debug + minified-release ICE/DTLS tests.
-dontwarn org.jni_zero.JniZeroJni
# Preserve the local privacy-safe crash frame filter after shrinking.
-keeppackagenames com.example.**
-keepnames class com.example.core.diagnostics.**
# AndroidX Test's separately built tracing bridge reflects/links this public API.
-keep class androidx.tracing.** { *; }
# AndroidJUnitRunner runs in a separate instrumentation APK. Its Kotlin runtime
# is resolved from the target APK; R8 can otherwise strip unused stdlib APIs
# (observed release-test crash: NoClassDefFoundError kotlin.LazyKt).
# Preserve the runtime library while still shrinking/obfuscating our app code.
-keep class kotlin.** { *; }
# Release instrumentation runs against a separately packaged AndroidJUnitRunner.
# Its test methods invoke kotlinx.coroutines.BuildersKt (runBlocking) even when
# no remaining production path references that JVM entrypoint. R8 stripped it
# in CI #351, causing three data-preservation tests to fail before assertions.
# Keeping the coroutine ABI avoids masking the regression by skipping tests.
-keep class kotlinx.coroutines.** { *; }
# The same release instrumentation suite also constructs temporary Room databases.
# Preserve the database/SQLite public ABI referenced only by the separate test
# APK; CI #354 proved the normal app can build while Room.class was stripped.
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
# Tests in the separate instrumentation APK explicitly instantiate the
# current v4 AppDatabase and its DAO/entity classes. Keep this ABI stable for
# destructive-migration regression checks on the minified release variant.
# This does not migrate or reset the existing user's Room database.
-keep class com.example.core.database.** { *; }
# The separate debug/release instrumentation APK starts a real opt-out worker.
# Preserve only the public WorkManager ABI it links; implementation still shrinks.
-keep class androidx.work.WorkManager { public *; }
# Kotlin callers use the Companion method, not only the @JvmStatic bridge.
# CI #401 proved release instrumentation needs both ABIs after R8 inlining.
-keep class androidx.work.WorkManager$Companion { public *; }
-keep class androidx.work.WorkInfo { public *; }
-keep class androidx.work.WorkInfo$State { *; }
-keep class androidx.work.WorkRequest { public *; }
-keep class androidx.work.WorkRequest$Builder { public protected *; }
-keep class androidx.work.OneTimeWorkRequest { public *; }
-keep class androidx.work.OneTimeWorkRequest$Builder { public protected *; }
-keep class androidx.work.Operation { public *; }
-keep class androidx.work.Data { public *; }
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

