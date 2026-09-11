package com.nearlink.app.data.repository

import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.data.local.dao.PeerDao
import com.nearlink.app.data.local.mapper.toDomain
import com.nearlink.app.data.local.mapper.toEntity
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PeerRepositoryImpl(
    private val dao: PeerDao,
    private val dispatchers: CoroutineDispatchers,
) : PeerRepository {

    override fun observePeers(): Flow<List<Peer>> =
        dao.observeAll()
            .map { entities -> entities.map { it.toDomain() } }
            // `toDomain` decodifica la clave publica en Base64: fuera del hilo
            // de la UI, que es quien recoge este flujo con `stateIn`.
            .flowOn(dispatchers.io)

    override suspend fun upsert(peer: Peer) {
        withContext(dispatchers.io) { dao.upsert(peer.toEntity()) }
    }

    override suspend fun forget(peerId: String) {
        withContext(dispatchers.io) { dao.delete(peerId) }
    }

    override suspend fun find(peerId: String): Peer? =
        withContext(dispatchers.io) { dao.find(peerId)?.toDomain() }

    override suspend fun all(): List<Peer> =
        withContext(dispatchers.io) { dao.all().map { it.toDomain() } }

    override suspend fun updateConnectionState(peerId: String, connected: Boolean) {
        withContext(dispatchers.io) {
            dao.updateConnectionState(
                peerId = peerId,
                state = if (connected) ConnectionState.CONNECTED.name else ConnectionState.DISCONNECTED.name,
            )
        }
    }

    override suspend fun setBlocked(peerId: String, blocked: Boolean) {
        withContext(dispatchers.io) { dao.setBlocked(peerId, blocked) }
    }
}
