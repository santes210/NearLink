package com.nearlink.app.di

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
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
import com.nearlink.app.data.local.db.NearLinkGroupDatabase
import com.nearlink.app.data.mesh.MeshInbox
import com.nearlink.app.data.repository.ChannelRepositoryImpl
import com.nearlink.app.data.repository.IdentityRepositoryImpl
import com.nearlink.app.data.repository.MessageRepositoryImpl
import com.nearlink.app.data.repository.PeerRepositoryImpl
import com.nearlink.app.data.repository.SettingsRepositoryImpl
import com.nearlink.app.data.transport.NearLinkTransport
import com.nearlink.app.domain.repository.ChannelRepository
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.MessageRepository
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.SettingsRepository
import com.nearlink.app.domain.repository.TransportRepository
import com.nearlink.app.domain.usecase.ConnectToPeerUseCase
import com.nearlink.app.domain.usecase.JoinChannelUseCase
import com.nearlink.app.domain.usecase.LeaveChannelUseCase
import com.nearlink.app.domain.usecase.PurgeExpiredMessagesUseCase
import com.nearlink.app.domain.usecase.RetryPendingMessagesUseCase
import com.nearlink.app.domain.usecase.SendAttachmentUseCase
import com.nearlink.app.domain.usecase.SendGroupMessageUseCase
import com.nearlink.app.domain.usecase.SendMessageUseCase
import com.nearlink.app.service.NearLinkNotifications
import com.nearlink.app.ui.screens.chat.ChatViewModel
import com.nearlink.app.ui.screens.groups.GroupChatViewModel
import com.nearlink.app.ui.screens.groups.GroupsViewModel
import com.nearlink.app.ui.screens.home.HomeViewModel
import com.nearlink.app.ui.screens.radar.RadarViewModel
import com.nearlink.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * Scope de aplicacion: vive mientras viva el proceso.
     *
     * Lleva un [CoroutineExceptionHandler] a proposito: este scope lanza el
     * trabajo de la malla y de las notificaciones sin que nadie haga `join`,
     * asi que una excepcion no capturada acabaria en el manejador por defecto
     * del proceso y CERRARIA la app (era lo que pasaba al recibir un mensaje).
     * Ahora se registra y la app sigue viva.
     */
    val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Excepcion no controlada en el scope de la malla", throwable)
            },
    )

    /**
     * Contador de Activities iniciadas, con API pura de Android (sin depender
     * de ProcessLifecycleOwner). Decide si hay que notificar o no.
     */
    private val foregroundTracker = ActivityCounter()

    init {
        (context.applicationContext as? Application)
            ?.registerActivityLifecycleCallbacks(foregroundTracker)
    }

    val dispatchers: CoroutineDispatchers = DefaultCoroutineDispatchers()

    val database: NearLinkDatabase by lazy { NearLinkDatabase.get(context) }

    val crypto: CryptoManager by lazy { CryptoManager(context.filesDir) }

    val attachmentStore: AttachmentStore by lazy { AttachmentStore(context.filesDir, crypto) }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(database.settingsDao(), dispatchers)
    }

    val identityRepository: IdentityRepository by lazy {
        IdentityRepositoryImpl(crypto, settingsRepository, dispatchers)
    }

    val peerRepository: PeerRepository by lazy {
        PeerRepositoryImpl(database.peerDao(), dispatchers)
    }

    val groupDatabase: NearLinkGroupDatabase by lazy { NearLinkGroupDatabase.get(context) }

    val channelRepository: ChannelRepository by lazy {
        ChannelRepositoryImpl(groupDatabase.channelDao(), crypto, identityRepository, dispatchers)
    }

    val messageCipher: MessageCipher by lazy {
        MessageCipher(crypto, identityRepository, peerRepository)
    }

    val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(
            messageDao = database.messageDao(),
            crypto = crypto,
            cipher = messageCipher,
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
            channelRepository = channelRepository,
            identityRepository = identityRepository,
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

    val joinChannel: JoinChannelUseCase by lazy {
        JoinChannelUseCase(channelRepository, transport, dispatchers)
    }

    val sendGroupMessage: SendGroupMessageUseCase by lazy {
        SendGroupMessageUseCase(channelRepository, transport, dispatchers)
    }

    val leaveChannel: LeaveChannelUseCase by lazy {
        LeaveChannelUseCase(channelRepository, dispatchers)
    }

    /**
     * Notificacion de mensaje entrante.
     *
     * Dos correcciones respecto a la version que colgaba la app:
     *  1. Se publica en un hilo de fondo, no en `Dispatchers.Main.immediate`.
     *     `NotificationManager` es thread-safe y cada `notify` es una llamada
     *     binder: meterla en el hilo principal sumaba bloqueos justo cuando
     *     llegaba un mensaje.
     *  2. Solo se notifica si la app NO esta en primer plano. Si el usuario ya
     *     tiene el chat abierto, la cabecera heads-up (canal IMPORTANCE_HIGH,
     *     con sonido y vibracion) no aporta nada y enmascaraba la UI.
     */
    private suspend fun notifyIncoming(peerId: String, preview: String) {
        if (isAppInForeground()) return
        val peer = peerRepository.find(peerId)
        val isSos = preview.contains("SOS", ignoreCase = true)
        val peerName = peer?.name ?: "Nodo NearLink"
        appScope.launch(dispatchers.default) {
            runCatching {
                NearLinkNotifications.showMessage(
                    context = context,
                    peerId = peerId,
                    peerName = peerName,
                    preview = preview,
                    isSos = isSos,
                )
            }.onFailure { Log.w(TAG, "No se pudo publicar la notificacion", it) }
        }
    }

    /** True si la app tiene una Activity visible (se usa para no notificar de mas). */
    private fun isAppInForeground(): Boolean = foregroundTracker.startedCount > 0

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

    val groupsViewModelFactory: ViewModelProvider.Factory get() = factory {
        GroupsViewModel(
            channelRepository = channelRepository,
            joinChannel = joinChannel,
            leaveChannel = leaveChannel,
            dispatchers = dispatchers,
        )
    }

    fun groupChatViewModelFactory(channelId: String): ViewModelProvider.Factory = factory {
        GroupChatViewModel(
            channelId = channelId,
            channelRepository = channelRepository,
            sendGroupMessage = sendGroupMessage,
            dispatchers = dispatchers,
        )
    }

    private companion object {
        const val TAG = "NearLink"
    }
}

/**
 * Contador de Activities en primer plano. Es la misma heuristica que usa
 * ProcessLifecycleOwner (STARTED/STOPPED), pero sin anadir una dependencia de
 * androidx.startup al arranque.
 */
private class ActivityCounter : Application.ActivityLifecycleCallbacks {

    @Volatile
    var startedCount: Int = 0
        private set

    override fun onActivityStarted(activity: Activity) {
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
