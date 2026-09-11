# NearLink - reglas de R8
#
# Se ofusca y se reduce el codigo en release, asi que hay que conservar:
#  - los modelos que se serializan a la BD (Room genera acceso por reflexion
#    controlada, pero los nombres de las columnas salen de las anotaciones),
#  - las clases usadas por Binder/parcelables del sistema (Bluetooth, WifiP2p),
#  - y cualquier cosa que se llame por reflexion.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Room
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep @androidx.room3.Entity class * { *; }
-dontwarn androidx.room3.paging.**
-dontwarn androidx.sqlite.**

# Modelos de dominio que se (des)serializan en el transporte
-keepclassmembers class com.nearlink.app.domain.model.** { <init>(...); }
-keepclassmembers class com.nearlink.app.data.local.entity.** { <init>(...); }

# Bluetooth / Wi-Fi Direct: clases de sistema que Android invoca por reflexion
-keepclassmembers class ** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-dontwarn android.net.wifi.p2p.**

# Crypto: el Keystore resuelve algoritmos por nombre en tiempo de ejecucion
-keepclassmembers class com.nearlink.app.data.crypto.** { *; }

# Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
