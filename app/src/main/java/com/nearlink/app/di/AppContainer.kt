package com.nearlink.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.DefaultCoroutineDispatchers
import com.nearlink.app.data.audio.VoicePlayer
import com.nearlink.app.data.audio.VoiceRecorder
import com.nearlink.app.data.crypto.CryptoManager
import com.nearlink.app.data.crypto.MessageCipher
import com.nearlink.app.data.local.AttachmentStore
import com.nearlink.app.data.local.db.NearLinkDatabase
import com.nearlink.app.data.mesh.MeshInbox
import com.nearlink.app.data.repository.IdentityRepositoryImpl
import com.nearlink.app.data.repository.MessageRepositoryImpl
import com.nearlink.app.data.repository.PeerRepositoryImpl
import com.nearlink.app.data.repository.SettingsRepositoryImpl
import com.nearlink.app.data.transport.NearLinkTransport
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.SettingsRepository
import com.nearlink.app.domain.repository.TransportRepository
import com.nearlink.app.domain.usecase.ConnectToPeerUseCase
import com.nearlink.app.domain.usecase.PurgeExpiredMessagesUseCase
import com.nearlink.app.domain.usecase.RetryPendingMessagesUseCase
import com.nearlink.app.domain.usecase.RotatePairingPinUseCase
import com.nearlink.app.domain.usecase.SendAttachmentUseCase
import com.nearlink.app.domain.usecase.SendMessageUseCase
import com.nearlink.app.service.NearLinkNotifications
import com.nearlink.app.ui.screens.chat.ChatViewModel
import com.nearlink.app.ui.screens.home.HomeViewModel
import com.nearlink.app.ui.screens.radar.RadarViewModel
import com.nearlink.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Contenedor de dependencias de la app (inyeccion manual).
 *
 * Se ha preferido un contenedor explicito a Hilt para no anadir el procesador
 * de anotaciones de Dagger a un proyecto de un solo modulo: menos superficie de
 * riesgo con Kotlin 2.4 / AGP 9 y cero reflection en arranque. Toda la
 * construccion es por constructor, asi que migrar a Hilt o a Kotlin Injection
 * seria mecanico.
 */
class AppContainer(private val context: Context) {

    /** Scope de aplicacion: vive mientras viva el proceso. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dispatchers: CoroutineDispatchers = DefaultCoroutineDispatchers()

    val database: NearLinkDatabase by lazy { NearLinkDatabase.get(context) }

    val crypto: CryptoManager by lazy { CryptoManager(context.filesDir) }

    val attachmentStore: AttachmentStore by lazy { AttachmentStore(context.filesDir, crypto) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(database.settingsDao(), crypto, dispatchers)
    }

    val identityRepository: IdentityRepository by lazy {
        IdentityRepositoryImpl(crypto, settingsRepository, dispatchers)
    }

    val peerRepository: PeerRepository by lazy {
        PeerRepositoryImpl(database.peerDao(), dispatchers)
    }

    val messageCipher: MessageCipher by lazy {
        MessageCipher(crypto, identityRepository, peerRepository)
    }

    val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(
            messageDao = database.messageDao(),
            crypto = crypto,
            cipher = messageCipher,
            peerRepository = peerRepository,
            attachmentStore = attachmentStore,
            dispatchers = dispatchers,
        )
    }

    val transport: TransportRepository by lazy {
        NearLinkTransport(
            context = context,
            identityRepository = identityRepository,
            peerRepository = peerRepository,
            settingsRepository = settingsRepository,
            dispatchers = dispatchers,
            scope = appScope,
        )
    }

    val meshInbox: MeshInbox by lazy {
        MeshInbox(
            transport = transport,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            cipher = messageCipher,
            dispatchers = dispatchers,
            scope = appScope,
            onIncomingMessage = { peerId, preview -> notifyIncoming(peerId, preview) },
        )
    }

    val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(context) }
    val voicePlayer: VoicePlayer by lazy { VoicePlayer() }

    val sendMessageUseCase: SendMessageUseCase by lazy {
        SendMessageUseCase(messageRepository, transport, dispatchers)
    }

    val sendAttachmentUseCase: SendAttachmentUseCase by lazy {
        SendAttachmentUseCase(messageRepository, transport, dispatchers, sendMessageUseCase)
    }

    val retryPendingMessages: RetryPendingMessagesUseCase by lazy {
        RetryPendingMessagesUseCase(messageRepository, sendMessageUseCase, dispatchers)
    }

    val purgeExpiredMessages: PurgeExpiredMessagesUseCase by lazy {
        PurgeExpiredMessagesUseCase(messageRepository, dispatchers)
    }

    val connectToPeer: ConnectToPeerUseCase by lazy {
        ConnectToPeerUseCase(transport, dispatchers)
    }

    val rotatePairingPin: RotatePairingPinUseCase by lazy {
        RotatePairingPinUseCase(settingsRepository, dispatchers)
    }

    /** Notificacion de mensaje entrante. */
    private suspend fun notifyIncoming(peerId: String, preview: String) {
        val peer = peerRepository.find(peerId)
        val isSos = preview.contains("SOS", ignoreCase = true)
        appScope.launch(Dispatchers.Main.immediate) {
            NearLinkNotifications.showMessage(
                context = context,
                peerId = peerId,
                peerName = peer?.name ?: "Nodo NearLink",
                preview = preview,
                isSos = isSos,
            )
        }
    }

    // ------------------------------------------------------------ factorias

    private inline fun <reified VM : ViewModel> factory(crossinline creator: () -> VM): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
        }

    val homeViewModelFactory: ViewModelProvider.Factory get() = factory {
        HomeViewModel(
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            transport = transport,
            identityRepository = identityRepository,
            sendMessage = sendMessageUseCase,
            dispatchers = dispatchers,
        )
    }

    val radarViewModelFactory: ViewModelProvider.Factory get() = factory {
        RadarViewModel(
            peerRepository = peerRepository,
            transport = transport,
            connectToPeer = connectToPeer,
            dispatchers = dispatchers,
        )
    }

    val settingsViewModelFactory: ViewModelProvider.Factory get() = factory {
        SettingsViewModel(
            settingsRepository = settingsRepository,
            identityRepository = identityRepository,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            rotatePairingPin = rotatePairingPin,
            dispatchers = dispatchers,
        )
    }

    fun chatViewModelFactory(peerId: String): ViewModelProvider.Factory = factory {
        ChatViewModel(
            peerId = peerId,
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            sendMessage = sendMessageUseCase,
            sendAttachment = sendAttachmentUseCase,
            connectToPeer = connectToPeer,
            dispatchers = dispatchers,
        )
    }
}
