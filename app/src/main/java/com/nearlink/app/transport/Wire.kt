package com.nearlink.app.transport

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Una entrada del directorio de contactos: huella (fingerprint) + llave pública X25519. */
data class ContactEntry(val fp: String, val pubKey: ByteArray)

/**
 * Mensaje del protocolo de NearLink (malla P2P). Viaja enmarcado por longitud
 * (4 bytes big-endian + payload UTF-8 JSON) sobre el canal Bluetooth / Wi-Fi Direct.
 *
 *  - [KeyExchange]: identidad propia (llave pública + huella). Puebla el directorio.
 *  - [Contacts]: gossip del directorio conocido (para aprender llaves de nodos lejanos).
 *  - [Envelope]: mensaje cifrado E2E para [recipientFp]; los relays lo reenvían sin leerlo.
 *  - [Ack]: confirmación de entrega (también reenviada por la malla).
 */
sealed class WireMessage {
    abstract fun toJson(): JSONObject

    data class KeyExchange(val publicKey: ByteArray, val fingerprint: String, val nonce: ByteArray) : WireMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "key_exchange")
            .put("pub", b64(publicKey))
            .put("fp", fingerprint)
            .put("nonce", b64(nonce))
    }

    data class Contacts(val entries: List<ContactEntry>) : WireMessage() {
        override fun toJson(): JSONObject {
            val arr = JSONArray()
            for (e in entries) arr.put(JSONObject().put("fp", e.fp).put("pub", b64(e.pubKey)))
            return JSONObject().put("type", "contacts").put("entries", arr)
        }
    }

    data class Envelope(
        val msgId: String,
        val recipientFp: String,
        val senderFp: String,
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val kind: String,
        val timestamp: Long,
        val ttlSeconds: Int,
        val fileName: String?,
        val hops: Int
    ) : WireMessage() {
        override fun toJson(): JSONObject {
            val o = JSONObject()
                .put("type", "envelope")
                .put("id", msgId)
                .put("to", recipientFp)
                .put("from", senderFp)
                .put("ct", b64(ciphertext))
                .put("iv", b64(iv))
                .put("kind", kind)
                .put("ts", timestamp)
                .put("ttl", ttlSeconds)
                .put("hops", hops)
            if (fileName != null) o.put("file", fileName)
            return o
        }
    }

    data class Ack(val msgId: String) : WireMessage() {
        override fun toJson(): JSONObject = JSONObject().put("type", "ack").put("id", msgId)
    }

    companion object {
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

        fun fromJson(s: String): WireMessage? = try {
            val o = JSONObject(s)
            when (o.getString("type")) {
                "key_exchange" -> KeyExchange(b64d(o.getString("pub")), o.getString("fp"), b64d(o.getString("nonce")))
                "contacts" -> {
                    val arr = o.getJSONArray("entries")
                    val list = mutableListOf<ContactEntry>()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        list.add(ContactEntry(e.getString("fp"), b64d(e.getString("pub"))))
                    }
                    Contacts(list)
                }
                "envelope" -> Envelope(
                    msgId = o.getString("id"),
                    recipientFp = o.getString("to"),
                    senderFp = o.getString("from"),
                    ciphertext = b64d(o.getString("ct")),
                    iv = b64d(o.getString("iv")),
                    kind = o.getString("kind"),
                    timestamp = o.getLong("ts"),
                    ttlSeconds = o.optInt("ttl", 0),
                    fileName = if (o.has("file")) o.getString("file") else null,
                    hops = o.optInt("hops", 0)
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
    private const val MAX_SIZE = 32 * 1024 * 1024 // 32 MB (archivos)

    fun write(out: DataOutputStream, msg: WireMessage) {
        val payload = msg.toJson().toString().toByteArray(Charsets.UTF_8)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun read(input: DataInputStream): WireMessage? {
        val size = try { input.readInt() } catch (e: IOException) { return null }
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
