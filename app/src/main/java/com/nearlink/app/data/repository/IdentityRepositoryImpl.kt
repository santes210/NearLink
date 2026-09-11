package com.nearlink.app.data.repository

import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.data.crypto.CryptoManager
import com.nearlink.app.domain.model.NodeIdentity
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Identidad del nodo: par P-256 guardado cifrado, derivacion ECDH del secreto
 * compartido y derivacion de la clave del PIN de emparejamiento.
 */
class IdentityRepositoryImpl(
    private val crypto: CryptoManager,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers,
) : IdentityRepository {

    private val mutex = Mutex()

    override suspend fun getIdentity(): NodeIdentity = withContext(dispatchers.io) {
        mutex.withLock {
            val publicKey = crypto.ensureIdentity().public.encoded
            NodeIdentity(
                publicKey = publicKey,
                fingerprint = crypto.fingerprint(publicKey),
                displayName = settingsRepository.current().displayName,
            )
        }
    }

    /**
     * Identidad del nodo.
     *
     * `flowOn(dispatchers.io)` es obligatorio aqui: `crypto.publicKeyBytes()` y
     * `crypto.fingerprint()` leen el fichero de identidad y tocan el Android
     * Keystore. Este flujo lo recoge `HomeViewModel` con
     * `stateIn(viewModelScope, ...)` (hilo principal), asi que sin el `flowOn`
     * el arranque de la pantalla de inicio hacia disco + Keystore en la UI.
     */
    override fun observeIdentity(): Flow<NodeIdentity> = flow {
        emit(getIdentity())
        emitAll(
            settingsRepository.settings.map { settings ->
                val publicKey = crypto.publicKeyBytes()
                NodeIdentity(
                    publicKey = publicKey,
                    fingerprint = crypto.fingerprint(publicKey),
                    displayName = settings.displayName,
                )
            },
        )
    }.flowOn(dispatchers.io)

    override suspend fun regenerate() {
        withContext(dispatchers.io) {
            mutex.withLock { crypto.regenerate() }
        }
    }

    override suspend fun rename(name: String) {
        settingsRepository.update { it.copy(displayName = name) }
    }

    override suspend fun sharedSecret(peerPublicKey: ByteArray): Outcome<ByteArray> =
        withContext(dispatchers.default) {
            try {
                Outcome.Success(crypto.sharedSecret(peerPublicKey))
            } catch (t: Throwable) {
                Outcome.Failure(t, "No se pudo derivar el secreto compartido: ${t.message}")
            }
        }

    override suspend fun derivePairingKey(pin: String, salt: ByteArray): ByteArray =
        withContext(dispatchers.default) { crypto.derivePinKey(pin, salt) }

    override suspend fun fingerprint(): String =
        withContext(dispatchers.io) { crypto.fingerprint() }

    override suspend fun publicKeyBytes(): ByteArray =
        withContext(dispatchers.io) { crypto.publicKeyBytes() }

    override suspend fun nodeId(): String =
        withContext(dispatchers.default) {
            crypto.sha256(crypto.publicKeyBytes()).take(6).joinToString(":") { "%02X".format(it) }
        }

    override suspend fun advertiseId(): ByteArray =
        withContext(dispatchers.default) {
            crypto.sha256(crypto.publicKeyBytes()).copyOf(4)
        }

    override suspend fun fingerprintOf(publicKey: ByteArray): String =
        withContext(dispatchers.default) { crypto.fingerprint(publicKey) }

    /** PIN aleatorio de 6 digitos. */
    fun generatePin(): String = crypto.randomPin(6)

    fun randomSalt(): ByteArray = crypto.randomBytes(16)
}
