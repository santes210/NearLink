# Add project specific ProGuard rules here.
# By default the flags in this file are applied to release builds with
# minifyEnabled = true. Debug builds are not affected.

# Keep Room generated classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
