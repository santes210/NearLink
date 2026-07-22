package com.nearlink.app.transport

import android.util.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Mensaje del protocolo de NearLink que viaja por el canal (Bluetooth / Wi-Fi Direct),
 * enmarcado por longitud (4 bytes big-endian + payload UTF-8 JSON).
 *
 * Flujo del handshake seguro:
 *   1) Ambos extremos envían [WireMessage.KeyExchange] (llave pública X25519 + huella + nonce).
 *   2) Cada uno deriva la llave de sesión con ECDH + HKDF (usa el PIN de emparejamiento).
 *   3) Los mensajes de app (TEXT/VOICE/FILE/SOS) viajan cifrados dentro de
 *      [WireMessage.Encrypted] junto con su IV y metadatos.
 *      [WireMessage.Ack] confirma recepción de un mensaje.
 */
sealed class WireMessage {
    abstract fun toJson(): JSONObject

    data class KeyExchange(
        val publicKey: ByteArray,
        val fingerprint: String,
        val nonce: ByteArray
    ) : WireMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "key_exchange")
            .put("pub", b64(publicKey))
            .put("fp", fingerprint)
            .put("nonce", b64(nonce))
    }

    data class Encrypted(
        val kind: String,        // TEXT / VOICE / FILE / SOS
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val id: String,
        val timestamp: Long,
        val ttlSeconds: Int,
        val fileName: String?
    ) : WireMessage() {
        override fun toJson(): JSONObject {
            val o = JSONObject()
                .put("type", "encrypted")
                .put("kind", kind)
                .put("ct", b64(ciphertext))
                .put("iv", b64(iv))
                .put("id", id)
                .put("ts", timestamp)
                .put("ttl", ttlSeconds)
            if (fileName != null) o.put("file", fileName)
            return o
        }
    }

    data class Ack(val id: String) : WireMessage() {
        override fun toJson(): JSONObject = JSONObject().put("type", "ack").put("id", id)
    }

    companion object {
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

        fun fromJson(s: String): WireMessage? = try {
            val o = JSONObject(s)
            when (o.getString("type")) {
                "key_exchange" -> KeyExchange(
                    b64d(o.getString("pub")),
                    o.getString("fp"),
                    b64d(o.getString("nonce"))
                )
                "encrypted" -> Encrypted(
                    o.getString("kind"),
                    b64d(o.getString("ct")),
                    b64d(o.getString("iv")),
                    o.getString("id"),
                    o.getLong("ts"),
                    o.optInt("ttl", 0),
                    if (o.has("file")) o.getString("file") else null
                )
                "ack" -> Ack(o.getString("id"))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Encuadra/desencuadra mensajes con una longitud de 4 bytes (big-endian). */
object MessageFramer {
    private const val MAX_SIZE = 16 * 1024 * 1024 // 16 MB tope por mensaje en el canal de control

    fun write(out: DataOutputStream, msg: WireMessage) {
        val payload = msg.toJson().toString().toByteArray(Charsets.UTF_8)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** Lee un mensaje; retorna null si el stream se cerró limpiamente. */
    fun read(input: DataInputStream): WireMessage? {
        val size = try {
            input.readInt()
        } catch (e: IOException) {
            return null
        }
        if (size <= 0 || size > MAX_SIZE) throw IOException("Tamaño de mensaje inválido: $size")
        val payload = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(payload, read, size - read)
            if (n < 0) throw IOException("Stream cerrado a mitad de mensaje")
            read += n
        }
        return WireMessage.fromJson(String(payload, Charsets.UTF_8))
    }
}
