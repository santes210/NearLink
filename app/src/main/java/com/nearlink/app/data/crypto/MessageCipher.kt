package com.nearlink.app.data.crypto

import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.PeerRepository

/**
 * Punto unico donde se decide con que clave se cifra/descifra un mensaje.
 *
 * Prioridad: secreto compartido derivado por ECDH con el par (E2E real) y, como
 * respaldo, la clave de reposo del dispositivo (mensajes encolados antes de que
 * existiera el handshake).
 */
class MessageCipher(
    private val crypto: CryptoManager,
    private val identityRepository: IdentityRepository,
    private val peerRepository: PeerRepository,
) {

    suspend fun keysFor(peerId: String): List<ByteArray> {
        val publicKey = peerRepository.find(peerId)?.publicKey
        val shared = publicKey?.let { key ->
            when (val result = identityRepository.sharedSecret(key)) {
                is Outcome.Success -> result.data
                is Outcome.Failure -> null
            }
        }
        val local = crypto.localKey()
        return if (shared != null && !shared.contentEquals(local)) {
            listOf(shared, local)
        } else {
            listOf(local)
        }
    }

    /** Devuelve la caja cifrada y si el cifrado es extremo a extremo. */
    suspend fun encrypt(peerId: String, plaintext: ByteArray): Pair<SealedBox, Boolean> {
        val keys = keysFor(peerId)
        return crypto.encrypt(plaintext, keys.first()) to (keys.size > 1)
    }

    suspend fun decrypt(peerId: String, box: SealedBox): ByteArray? {
        val keys = keysFor(peerId)
        return keys.firstNotNullOfOrNull { key ->
            runCatching { crypto.decrypt(box, key) }.getOrNull()
        }
    }

    suspend fun isEndToEnd(peerId: String): Boolean = keysFor(peerId).size > 1
}
