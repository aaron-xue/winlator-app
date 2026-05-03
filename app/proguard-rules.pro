# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
-dontobfuscate

# Keep all enum classes to prevent R8 NullPointerException
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Winlator classes that may be accessed via reflection or JNI
-keep class com.winlator.** { *; }

# Keep zstd-jni classes used by native code
-keep class com.github.luben.zstd.** { *; }

# Keep apache commons compress
-keep class org.apache.commons.compress.** { *; }

# Ignore missing optional dependencies of apache commons compress
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn com.github.luben.zstd.**