package com.nearlink.app.data.security

import android.content.Context
import java.security.PrivateKey

/**
 * Identidad criptográfica del dispositivo: un par de llaves X25519 permanente.
 *
 * La llave pública se guarda en claro (es pública) y la privada se guarda CIFRADA
 * con una llave AES-256 respaldada por AndroidKeyStore (vía [EncryptionManager]),
 * en SharedPreferences. Así la identidad sobrevive reinicios y es la base de la
 * huella (fingerprint) que se compara para verificar al par.
 */
class IdentityManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val encryption = EncryptionManager()

    private val privateKey: PrivateKey
    val publicKeyBytes: ByteArray

    val fingerprint: String

    init {
        val (priv, pub) = loadOrCreate()
        privateKey = priv
        publicKeyBytes = pub
        fingerprint = Crypto.fingerprint(pub)
    }

    fun privateKey(): PrivateKey = privateKey

    private fun loadOrCreate(): Pair<PrivateKey, ByteArray> {
        val encPriv = prefs.getString(KEY_PRIV, null)
        val iv = prefs.getString(KEY_IV, null)
        val pubB64 = prefs.getString(KEY_PUB, null)
        if (encPriv != null && iv != null && pubB64 != null) {
            try {
                val privBytes = encryption.decryptBytes(encPriv, iv)
                val priv = Crypto.decodePrivateKey(privBytes)
                val pub = Crypto.b64decode(pubB64)
                return priv to pub
            } catch (e: Exception) {
                // registro corrupto -> regenerar
            }
        }
        val kp = Crypto.generateKeyPair()
        val privBytes = kp.private.encoded
        val pub = kp.public.encoded
        val (enc, newIv) = encryption.encryptBytes(privBytes)
        prefs.edit()
            .putString(KEY_PRIV, enc)
            .putString(KEY_IV, newIv)
            .putString(KEY_PUB, Crypto.b64(pub))
            .apply()
        return kp.private to pub
    }

    companion object {
        private const val PREFS = "nearlink_identity"
        private const val KEY_PRIV = "priv_enc"
        private const val KEY_IV = "priv_iv"
        private const val KEY_PUB = "pub"
    }
}
