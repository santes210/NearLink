# Keep NearLink models for Room / serialization
-keep class com.nearlink.app.data.local.** { *; }
-keep class com.nearlink.app.domain.model.** { *; }
-keep class androidx.room.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Material3 / Compose - keep composables
-keep class androidx.compose.** { *; }

# Encryption - keep Keystore
-keep class javax.crypto.** { *; }
-keep class android.security.keystore.** { *; }

# Remove logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
