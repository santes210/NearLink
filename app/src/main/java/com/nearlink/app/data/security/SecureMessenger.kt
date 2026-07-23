package com.nearlink.app.data.security

import android.content.Context
import com.nearlink.app.transport.ContactEntry
import com.nearlink.app.transport.WireMessage
import org.json.JSONArray
import org.json.JSONObject

/** Mensaje de aplicación ya descifrado. */
data class AppMessage(
    val id: String,
    val senderFp: String,
    val kind: String,
    val content: String,
    val timestamp: Long,
    val ttlSeconds: Int,
    val fileName: String?,
    val hops: Int
)

/** Resultado de procesar un mensaje entrante (el ViewModel decide qué hacer). */
sealed class HandleResult {
    /** Mensaje dirigido a MÍ y descifrado correctamente. */
    data class ForMe(val app: AppMessage) : HandleResult()
    /** Mensaje para OTRO nodo: hay que reenviarlo (un salto menos). */
    data class Relay(val envelope: WireMessage.Envelope) : HandleResult()
    /** Recibí identidad/directorio: conviene responder con mi directorio + outbox. */
    object Directory : HandleResult()
    /** Confirmación de entrega de un mensaje MÍO saliente. */
    data class Ack(val msgId: String) : HandleResult()
    /** Duplicado o inválido: ignorar. */
    object Ignore : HandleResult()
}

/**
 * Núcleo de la malla NearLink: cifrado E2E a la identidad del destinatario +
 * almacenar-y-reenviar (store-and-forward) + gossip del directorio + deduplicación
 * + límite de saltos (16).
 *
 * Cada [WireMessage.Envelope] se cifra para [recipientFp] (la identidad del
 * destinatario). Los nodos intermedios solo lo reenvían: nunca pueden descifrarlo.
 */
class SecureMessenger(
    private val identity: IdentityManager,
    val contactStore: ContactStore,
    context: Context,
    private val pairingPin: () -> String
) {
    private val meshPrefs = context.getSharedPreferences("nearlink_mesh", Context.MODE_PRIVATE)

    private val seen: MutableSet<String> = loadSeen()
    private val outbox: MutableList<WireMessage.Envelope> = loadOutbox()

    // ---------------- Saliente ----------------

    fun sealToRecipient(
        recipientFp: String,
        kind: String,
        content: String,
        ttlSeconds: Int,
        fileName: String?
    ): WireMessage.Envelope? {
        val recipientPub = contactStore.get(recipientFp) ?: return null
        val key = Crypto.e2eKey(identity.privateKey(), Crypto.decodePublicKey(recipientPub), pairingPin())
        val payload = JSONObject().put("content", content).toString().toByteArray(Charsets.UTF_8)
        val msgId = "m" + System.currentTimeMillis()
        val enc = Crypto.encrypt(key, payload, msgId.toByteArray(Charsets.UTF_8))
        val envelope = WireMessage.Envelope(
            msgId = msgId,
            recipientFp = recipientFp,
            senderFp = identity.fingerprint,
            ciphertext = enc.ciphertext,
            iv = enc.iv,
            kind = kind,
            timestamp = System.currentTimeMillis(),
            ttlSeconds = ttlSeconds,
            fileName = fileName,
            hops = MAX_HOPS
        )
        addToOutbox(envelope)
        return envelope
    }

    fun beginHandshake(): WireMessage.KeyExchange =
        WireMessage.KeyExchange(identity.publicKeyBytes, identity.fingerprint, Crypto.randomBytes(16))

    fun buildContacts(): WireMessage.Contacts {
        val list = ArrayList(contactStore.entries())
        list.add(ContactEntry(identity.fingerprint, identity.publicKeyBytes))
        return WireMessage.Contacts(list)
    }

    fun buildAck(msgId: String): WireMessage.Ack = WireMessage.Ack(msgId)

    fun outboxSnapshot(): List<WireMessage.Envelope> = outbox.toList()

    val myFingerprint: String get() = identity.fingerprint

    // ---------------- Entrante ----------------

    fun handleIncoming(msg: WireMessage): HandleResult {
        return when (msg) {
            is WireMessage.KeyExchange -> {
                contactStore.put(msg.fingerprint, msg.publicKey)
                HandleResult.Directory
            }
            is WireMessage.Contacts -> {
                contactStore.merge(msg.entries)
                HandleResult.Directory
            }
            is WireMessage.Envelope -> {
                if (!seen.add(msg.msgId)) return HandleResult.Ignore
                markSeen(msg.msgId)
                when {
                    msg.recipientFp == identity.fingerprint -> {
                        val app = openForMe(msg) ?: return HandleResult.Ignore
                        HandleResult.ForMe(app)
                    }
                    msg.hops > 0 -> {
                        val forwarded = msg.copy(hops = msg.hops - 1)
                        addToOutbox(forwarded)
                        HandleResult.Relay(forwarded)
                    }
                    else -> HandleResult.Ignore // se agotaron los saltos
                }
            }
            is WireMessage.Ack -> {
                if (removeFromOutbox(msg.msgId)) HandleResult.Ack(msg.msgId) else HandleResult.Ignore
            }
        }
    }

    private fun openForMe(envelope: WireMessage.Envelope): AppMessage? {
        val senderPub = contactStore.get(envelope.senderFp) ?: return null
        return runCatching {
            val key = Crypto.e2eKey(identity.privateKey(), Crypto.decodePublicKey(senderPub), pairingPin())
            val plain = Crypto.decrypt(key, envelope.ciphertext, envelope.iv, envelope.msgId.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(plain, Charsets.UTF_8))
            AppMessage(
                id = envelope.msgId,
                senderFp = envelope.senderFp,
                kind = envelope.kind,
                content = json.optString("content"),
                timestamp = envelope.timestamp,
                ttlSeconds = envelope.ttlSeconds,
                fileName = envelope.fileName,
                hops = MAX_HOPS - envelope.hops // saltos recorridos aprox.
            )
        }.getOrNull()
    }

    // ---------------- Persistencia de outbox / seen ----------------

    private fun addToOutbox(envelope: WireMessage.Envelope) {
        // Expira entradas viejas (>24h) para que la bandeja no crezca indefinidamente.
        val cutoff = System.currentTimeMillis() - OUTBOX_TTL_MS
        if (outbox.removeAll { it.timestamp < cutoff }) { /* follows below */ }
        if (outbox.none { it.msgId == envelope.msgId }) {
            outbox.add(envelope)
            if (outbox.size > MAX_OUTBOX) outbox.removeAt(0)
            persistOutbox()
        }
    }

    private fun removeFromOutbox(msgId: String): Boolean {
        val removed = outbox.removeAll { it.msgId == msgId }
        if (removed) persistOutbox()
        return removed
    }

    private fun markSeen(msgId: String) {
        if (seen.size > MAX_SEEN) {
            val iter = seen.iterator()
            repeat(MAX_SEEN / 2) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        persistSeen()
    }

    private fun persistOutbox() {
        val arr = JSONArray()
        for (e in outbox) arr.put(e.toJson())
        meshPrefs.edit().putString(KEY_OUTBOX, arr.toString()).apply()
    }

    private fun loadOutbox(): MutableList<WireMessage.Envelope> {
        val list = mutableListOf<WireMessage.Envelope>()
        meshPrefs.getString(KEY_OUTBOX, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val w = WireMessage.fromJson(arr.getJSONObject(i).toString())
                    if (w is WireMessage.Envelope) list.add(w)
                }
            } catch (_: Exception) {
            }
        }
        return list
    }

    private fun persistSeen() {
        val arr = JSONArray()
        for (s in seen) arr.put(s)
        meshPrefs.edit().putString(KEY_SEEN, arr.toString()).apply()
    }

    private fun loadSeen(): MutableSet<String> {
        val s = mutableSetOf<String>()
        meshPrefs.getString(KEY_SEEN, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) s.add(arr.getString(i))
            } catch (_: Exception) {
            }
        }
        return s
    }

    companion object {
        const val MAX_HOPS = 16
        const val MAX_OUTBOX = 300
        const val MAX_SEEN = 5000
        const val OUTBOX_TTL_MS = 24L * 60 * 60 * 1000
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_SEEN = "seen"
    }
}
