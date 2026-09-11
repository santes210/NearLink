package com.nearlink.app.domain.usecase

import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.Channel
import com.nearlink.app.domain.model.GroupMessage
import com.nearlink.app.domain.repository.ChannelRepository
import com.nearlink.app.domain.repository.TransportRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Crea un grupo nuevo o se reincorpora a uno existente usando el código común.
 * Todos los dispositivos que escriban el MISMO código derivan el mismo canal y
 * la misma clave, así que pueden mensajearse entre sí (incluidos los saltos por
 * la malla).
 */
class JoinChannelUseCase(
    private val channelRepository: ChannelRepository,
    private val transport: TransportRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(code: String, name: String): Outcome<Channel> = withContext(dispatchers.io) {
        when (val result = channelRepository.join(code, name)) {
            is Outcome.Success -> {
                // Descubre nodos cercanos y, tras una breve ventana, enlaza con
                // los que haya encontrado para que la difusión llegue de
                // inmediato (el escaneo sigue activo y se autodetiene).
                transport.startScan()
                delay(DISCOVERY_GRACE_MS)
                transport.connectAllKnown()
                result
            }

            is Outcome.Failure -> result
        }
    }

    companion object {
        /** Ventana de descubrimiento antes de intentar enlazar con los nodos vistos. */
        private const val DISCOVERY_GRACE_MS = 3_000L
    }
}

/**
 * Envía un mensaje a un grupo: se cifra con la clave del canal y se difunde a
 * toda la malla (los repetidores lo propagan). Sin ACK extremo a extremo, es un
 * envío de mejor esfuerzo propio de un broadcast de malla.
 */
class SendGroupMessageUseCase(
    private val channelRepository: ChannelRepository,
    private val transport: TransportRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(channelId: String, content: String): Outcome<GroupMessage> =
        withContext(dispatchers.io) {
            val text = content.trim()
            if (text.isEmpty()) return@withContext Outcome.Failure(message = "Mensaje vacío")
            when (val prepared = channelRepository.enqueueGroupMessage(channelId, text)) {
                is Outcome.Failure -> prepared
                is Outcome.Success -> {
                    // El mensaje ya está guardado y visible; ahora se difunde.
                    transport.connectAllKnown()
                    transport.broadcast(prepared.data.payload)
                    Outcome.Success(prepared.data.message)
                }
            }
        }
}

/** Abandona un grupo: borra el canal, sus mensajes y su lista de miembros. */
class LeaveChannelUseCase(
    private val channelRepository: ChannelRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    suspend operator fun invoke(channelId: String) {
        withContext(dispatchers.io) { channelRepository.leave(channelId) }
    }
}
