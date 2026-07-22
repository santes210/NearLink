package com.nearlink.app.data.security

import com.nearlink.app.transport.WireMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Mensaje de aplicación ya descifrado que sube hacia la UI/repositorio. */
data class AppMessage(
    val id: String,
    val kind: String,
    val content: String,
    val timestamp: Long,
    val ttlSeconds: Int,
    val fileName: String?
)

/**
 * Orquesta el handshake ECDH X25519 sobre el transporte y cifra/descifra los
 * mensajes de aplicación. No conoce Bluetooth ni Wi-Fi: solo el protocolo y la
 * criptografía. El ViewModel lo conecta con el transporte real.
 *
 * El PIN de emparejamiento entra en la derivación HKDF, de modo que dos dispositivos
 * solo logran derivar la MISMA llave de sesión si comparten el mismo PIN. Esto
 * autentica el canal contra un atacante man-in-the-middle que no conozca el PIN.
 */
class SecureMessenger(
    private val identity: IdentityManager,
    private val pairingPin: () -> String
) {
    private val _decrypted = MutableSharedFlow<AppMessage>(extraBufferCapacity = 64)
    val decrypted: SharedFlow<AppMessage> = _decrypted.asSharedFlow()

    private val _peerFingerprint = MutableStateFlow<String?>(null)
    val peerFingerprint: StateFlow<String?> = _peerFingerprint.asStateFlow()

    @Volatile
    private var sessionKey: ByteArray? = null

    fun hasSession(): Boolean = sessionKey != null
    fun fileKey(): ByteArray? = sessionKey?.let { Crypto.deriveFileKey(it) }

    /** Procesa un WireMessage entrante. true = consumido por la capa segura. */
    fun handleIncoming(msg: WireMessage): Boolean {
        return when (msg) {
            is WireMessage.KeyExchange -> {
                try {
                    if (rememberedNonce == null) rememberedNonce = Crypto.randomBytes(16)
                    val peerPub = Crypto.decodePublicKey(msg.publicKey)
                    val secret = Crypto.sharedSecret(identity.privateKey(), peerPub)
                    val salt = Crypto.concatSorted(rememberedNonce!!, msg.nonce)
                    sessionKey = Crypto.deriveSessionKey(secret, salt, pairingPin())
                    _peerFingerprint.value = msg.fingerprint
                } catch (e: Exception) {
                    sessionKey = null
                }
                true
            }
            is WireMessage.Encrypted -> {
                val key = sessionKey ?: return false
                try {
                    val plain = Crypto.decrypt(key, msg.ciphertext, msg.iv, msg.id.toByteArray(Charsets.UTF_8))
                    val json = JSONObject(String(plain, Charsets.UTF_8))
                    _decrypted.tryEmit(
                        AppMessage(
                            id = msg.id,
                            kind = msg.kind,
                            content = json.optString("content"),
                            timestamp = msg.timestamp,
                            ttlSeconds = msg.ttlSeconds,
                            fileName = msg.fileName
                        )
                    )
                } catch (e: Exception) {
                    // descifrado fallido (PIN distinto / manipulación): se descarta
                }
                true
            }
            is WireMessage.Ack -> true
        }
    }

    // Nonce propio que enviamos en el KeyExchange; se recuerda para derivar el mismo salt.
    // Idempotente: si ya se generó (o lo fijó un KeyExchange entrante) se reutiliza, de modo
    // que el nonce que enviamos y con el que derivamos coincidan siempre.
    @Volatile
    private var rememberedNonce: ByteArray? = null

    /** Construye el KeyExchange que debe enviarse al conectarse. Idempotente. */
    fun beginHandshake(): WireMessage.KeyExchange {
        if (rememberedNonce == null) rememberedNonce = Crypto.randomBytes(16)
        return WireMessage.KeyExchange(
            publicKey = identity.publicKeyBytes,
            fingerprint = identity.fingerprint,
            nonce = rememberedNonce!!
        )
    }

    /** Cifra un mensaje de aplicación para enviarlo. Null si aún no hay sesión. */
    fun encryptAppMessage(
        kind: String,
        content: String,
        id: String,
        ttlSeconds: Int,
        fileName: String?
    ): WireMessage.Encrypted? {
        val key = sessionKey ?: return null
        val payload = JSONObject().put("content", content).toString().toByteArray(Charsets.UTF_8)
        val enc = Crypto.encrypt(key, payload, id.toByteArray(Charsets.UTF_8))
        return WireMessage.Encrypted(
            kind = kind,
            ciphertext = enc.ciphertext,
            iv = enc.iv,
            id = id,
            timestamp = System.currentTimeMillis(),
            ttlSeconds = ttlSeconds,
            fileName = fileName
        )
    }

    fun reset() {
        sessionKey = null
        rememberedNonce = null
        _peerFingerprint.value = null
    }
}
