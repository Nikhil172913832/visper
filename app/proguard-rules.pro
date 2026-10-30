# R8 / ProGuard Optimization Rules for Visper (Minimal On-Device AI)

# Compose rules
-keep class androidx.compose.material3.** { *; }
-dontwarn androidx.compose.**

# Coroutines optimizations
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# MediaPipe GenAI & LiteRT native bindings
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# llama.cpp Android JNI bindings
-keep class com.arm.aichat.** { *; }
-keepclassmembers class com.arm.aichat.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @com.google.mediapipe.** <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Suppress compile-time annotations & proto warnings in MediaPipe / AutoValue
-dontwarn com.google.mediapipe.proto.**
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**

# Ensure JNI exported methods aren't stripped
-keepclasseswithmembers class * {
    native <methods>;
}

# Strip debug logs in release builds for maximum performance
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
