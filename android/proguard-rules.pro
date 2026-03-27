# Keep LogSessionId class and related classes
-keep class android.media.metrics.LogSessionId { *; }
-keep class android.media.metrics.** { *; }

# Keep Media3 classes that use reflection
-keep class androidx.media3.** { *; }
-dontwarn android.media.metrics.**

# Alternative: If you want to be more specific
-keepclassmembers class androidx.media3.transformer.DefaultAssetLoaderFactory {
    *;
}