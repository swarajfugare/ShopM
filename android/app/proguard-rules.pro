# ==============================================================================
# MATOSHREE COLLECTION — PROGUARD & R8 SECURITY CONFIGURATION
# ==============================================================================

# Keep Firebase DTOs and models for accurate serialization/deserialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.matoshree.shopmanager.data.firebase.** { *; }
-keepclassmembers class com.matoshree.shopmanager.data.firebase.** { *; }
-keep class com.matoshree.shopmanager.data.local.entity.** { *; }
-keepclassmembers class com.matoshree.shopmanager.data.local.entity.** { *; }
-keep class com.matoshree.shopmanager.domain.model.** { *; }
-keepclassmembers class com.matoshree.shopmanager.domain.model.** { *; }
# Keep AndroidX Biometric and Security Crypto
-keep class androidx.biometric.** { *; }
-keep class androidx.security.crypto.** { *; }

# Keep ZXing & CameraX for Barcode Scanning
-keep class com.google.zxing.** { *; }
-keep class androidx.camera.** { *; }

# Strip verbose and debug logging from release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Preserve stack trace line numbers for crash debugging while obfuscating class names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
