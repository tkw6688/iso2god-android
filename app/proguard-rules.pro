# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the ProgressCallback interface (used by JNI callback)
-keep class com.thehbc.iso2god.MainActivity$ProgressCallback { *; }

# Keep IsoInfo data class (used by JSON parsing)
-keep class com.thehbc.iso2god.IsoInfo { *; }

# Keep MainActivity external methods
-keep class com.thehbc.iso2god.MainActivity {
    native <methods>;
}

# Optimization without obfuscation
-dontobfuscate
