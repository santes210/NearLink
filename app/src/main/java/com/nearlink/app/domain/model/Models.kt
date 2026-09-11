package com.nearlink.app.domain.model

/** Tipos de mensaje que entiende la malla. */
enum class MessageType {
    TEXT,
    VOICE,
    FILE,
    SOS,
}

/** Ciclo de vida de un mensaje. */
enum class MessageStatus {
    /** En cola, todavia no se ha podido abrir el enlace con el destino. */
    QUEUED,

    /** Entregandose por el transporte (BLE / Wi-Fi Direct). */
    SENDING,

    /** Escrito en el enlace; esperando ACK del receptor. */
    SENT,

    /** ACK recibido del nodo destino. */
    DELIVERED,

    /** El receptor abrio la conversacion despues de recibirlo. */
    READ,

    /** No se pudo entregar (enlace caido, TTL agotado, error de cifrado...). */
    FAILED,
}

/** Estado del enlace con un nodo. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED,
}

/** Tipo de trama que viaja por la malla. */
enum class FrameType {
    /** Presentacion: intercambio de claves publicas. */
    HELLO,

    /** Respuesta al HELLO. */
    HELLO_ACK,

    /** Mensaje de texto cifrado. */
    MESSAGE,

    /** Confirmacion de recepcion. */
    ACK,

    /** Fichero / nota de voz. */
    FILE,

    /** Alerta de emergencia. */
    SOS,
}

/** Estado del radar de descubrimiento. */
enum class ScanState {
    IDLE,
    SCANNING,
    UNAVAILABLE,
    ERROR,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Identidad criptografica del nodo local. */
data class NodeIdentity(
    /** Clave publica codificada en X.509 (P-256). Se comparte en el handshake. */
    val publicKey: ByteArray,
    /** Clave privada (solo si el almacen la devuelve; normalmente null: Keystore). */
    val fingerprint: String,
    val displayName: String,
) {
    /** Huella corta legible para verificacion en persona. */
    val shortFingerprint: String = fingerprint.take(19)

    override fun equals(other: Any?): Boolean =
        this === other || (other is NodeIdentity && fingerprint == other.fingerprint && publicKey.contentEquals(other.publicKey))

    override fun hashCode(): Int = 31 * fingerprint.hashCode() + publicKey.contentHashCode()
}

/** Adjunto (nota de voz o fichero) asociado a un mensaje. */
data class Attachment(
    val path: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
)

/** Un nodo visto en la malla. */
data class Peer(
    /** Direccion MAC BLE (identificador estable de la malla). */
    val id: String,
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeen: Long,
    val connectionState: ConnectionState,
    /** Huella de la clave publica del par, null si aun no hubo handshake. */
    val fingerprint: String? = null,
    /** Clave publica X.509 (P-256) del par, necesaria para derivar el ECDH. */
    val publicKey: ByteArray? = null,
    /** True si el nodo actua como repetidor de la malla. */
    val relay: Boolean = false,
    /** Saltos hasta el nodo (0 = enlace directo). */
    val hops: Int = 0,
    val verified: Boolean = false,
    val blocked: Boolean = false,
) {
    /** Etiqueta legible de la calidad de la senal. */
    val signalQuality: SignalQuality
        get() = when {
            rssi >= -60 -> SignalQuality.EXCELLENT
            rssi >= -75 -> SignalQuality.GOOD
            rssi >= -90 -> SignalQuality.FAIR
            else -> SignalQuality.WEAK
        }
}

enum class SignalQuality { EXCELLENT, GOOD, FAIR, WEAK }

/** Mensaje tal y como lo consume la UI (siempre descifrado). */
data class Message(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val content: String,
    val timestamp: Long,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    val attachment: Attachment? = null,
    /** Segundos de autodestruccion; 0 = permanente. */
    val ttlSeconds: Int = 0,
    /** Epoch millis en el que expira; null si no caduca. */
    val expiresAt: Long? = null,
    val hops: Int = 0,
    val relayed: Boolean = false,
) {
    val isSos: Boolean get() = type == MessageType.SOS

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= now
}

/** Resumen de una conversacion para la pantalla de inicio. */
data class Conversation(
    val peerId: String,
    val peerName: String,
    val preview: String,
    val lastActivity: Long,
    val unreadCount: Int,
    val isConnected: Boolean,
    val rssi: Int,
    val fingerprint: String?,
)

/** Preferencias del usuario. */
data class UserSettings(
    val displayName: String = "Nodo NearLink",
    val defaultTtlSeconds: Int = 0,
    val relayEnabled: Boolean = true,
    val autoDeleteEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val wifiDirectThresholdMb: Int = 5,
    /** PIN efimero para emparejar por proximidad. */
    val pairingPin: String = "",
    val pinExpiresAt: Long = 0L,
)

/** Estado global del transporte de la malla. */
data class TransportStatus(
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val connectedPeers: Int = 0,
    val bluetoothEnabled: Boolean = false,
    val message: String? = null,
)
