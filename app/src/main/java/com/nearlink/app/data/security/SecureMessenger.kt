package com.nearlink.app.data.security

import android.content.Context
import com.nearlink.app.transport.ContactEntry
import com.nearlink.app.transport.WireMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Mensaje de aplicación ya descifrado que sube hacia la UI/repositorio. */
data class AppMessage(
    val id: String,
    val senderFp: String,
    val kind: String,
    val content: String,
    val timestamp: Long,
    val ttlSeconds: Int,
    val fileName: String?
)

/**
 * Núcleo de la malla NearLink: cifrado E2E a la identidad del destinatario +
 * almacenar-y-reenviar (store-and-forward) + gossip del directorio + deduplicación
 * + límite de saltos.
 *
 * Cada [WireMessage.Envelope] se cifra para [recipientFp] (la identidad del
 * destinatario). Los nodos intermedios solo lo reenvían: nunca pueden descifrarlo.
 * Cuando un nodo recibe un sobre que NO es para él, lo guarda en la bandeja de
 * salida ([outbox]) para reenviarlo a los siguientes pares con los que conecte,
 * reduciendo [hops] en cada salto.
 */
class SecureMessenger(
    private val identity: IdentityManager,
    val contactStore: ContactStore,
    private val context: Context,
    private val pairingPin: () -> String
) {
    private val meshPrefs = context.getSharedPreferences("nearlink_mesh", Context.MODE_PRIVATE)

    /** Mensajes que YO recibí y descifré (para persistirlos y, opcionalmente, confirmar). */
    private val _decrypted = MutableSharedFlow<AppMessage>(extraBufferCapacity = 64)
    val decrypted: SharedFlow<AppMessage> = _decrypted.asSharedFlow()

    /** IDs de MIS mensajes salientes cuya entrega se confirmó (Ack). */
    private val _deliveryAcks = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val deliveryAcks: SharedFlow<String> = _deliveryAcks.asSharedFlow()

    private val _peerFingerprint = MutableStateFlow<String?>(null)
    val peerFingerprint: StateFlow<String?> = _peerFingerprint.asStateFlow()

    @Volatile private var needFlush = false

    private val seen: MutableSet<String> = loadSeen()
    private val outbox: MutableList<WireMessage.Envelope> = loadOutbox()

    // ---------------- Saliente ----------------

    /**
     * Cifra [content] E2E para [recipientFp] y lo deja en la bandeja de salida.
     * Devuelve el sobre para enviarlo de inmediato al par conectado, o null si no
     * conocemos la llave pública del destinatario (todavía no llegó por gossip).
     */
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

    /** Construye la identidad propia para enviarla al conectar. */
    fun beginHandshake(): WireMessage.KeyExchange =
        WireMessage.KeyExchange(identity.publicKeyBytes, identity.fingerprint, Crypto.randomBytes(16))

    /** Directorio de contactos (incluida la propia identidad) para hacer gossip. */
    fun buildContacts(): WireMessage.Contacts {
        val list = ArrayList(contactStore.entries())
        list.add(ContactEntry(identity.fingerprint, identity.publicKeyBytes))
        return WireMessage.Contacts(list)
    }

    fun buildAck(msgId: String): WireMessage.Ack = WireMessage.Ack(msgId)

    fun outboxSnapshot(): List<WireMessage.Envelope> = outbox.toList()

    // ---------------- Entrante ----------------

    /** Procesa un mensaje entrante. Devuelve true si se consumió. */
    fun handleIncoming(msg: WireMessage): Boolean {
        return when (msg) {
            is WireMessage.KeyExchange -> {
                contactStore.put(msg.fingerprint, msg.publicKey)
                _peerFingerprint.value = msg.fingerprint
                needFlush = true
                true
            }
            is WireMessage.Contacts -> {
                contactStore.merge(msg.entries)
                needFlush = true
                true
            }
            is WireMessage.Envelope -> {
                if (!seen.add(msg.msgId)) return true // ya visto: descartar (anti-bucle)
                markSeen(msg.msgId)
                if (msg.recipientFp == identity.fingerprint) {
                    openForMe(msg) // emite por _decrypted; el ViewModel confirma con un Ack
                } else if (msg.hops > 0) {
                    addToOutbox(msg.copy(hops = msg.hops - 1)) // reenviar (store-and-forward)
                }
                true
            }
            is WireMessage.Ack -> {
                if (removeFromOutbox(msg.msgId)) _deliveryAcks.tryEmit(msg.msgId)
                true
            }
        }
    }

    /** ¿Recibimos KeyExchange/Contacts y conviene responder con nuestro directorio + outbox? */
    fun consumeFlushRequest(): Boolean {
        val f = needFlush
        needFlush = false
        return f
    }

    private fun openForMe(envelope: WireMessage.Envelope) {
        val senderPub = contactStore.get(envelope.senderFp) ?: return
        runCatching {
            val key = Crypto.e2eKey(identity.privateKey(), Crypto.decodePublicKey(senderPub), pairingPin())
            val plain = Crypto.decrypt(key, envelope.ciphertext, envelope.iv, envelope.msgId.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(plain, Charsets.UTF_8))
            _decrypted.tryEmit(
                AppMessage(
                    id = envelope.msgId,
                    senderFp = envelope.senderFp,
                    kind = envelope.kind,
                    content = json.optString("content"),
                    timestamp = envelope.timestamp,
                    ttlSeconds = envelope.ttlSeconds,
                    fileName = envelope.fileName
                )
            )
        }
    }

    fun reset() {
        _peerFingerprint.value = null
        needFlush = false
    }

    // ---------------- Persistencia de outbox / seen ----------------

    private fun addToOutbox(envelope: WireMessage.Envelope) {
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
        const val MAX_HOPS = 8
        const val MAX_OUTBOX = 200
        const val MAX_SEEN = 2000
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_SEEN = "seen"
    }
}
