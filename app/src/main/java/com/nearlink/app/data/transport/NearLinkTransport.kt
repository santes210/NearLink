package com.nearlink.app.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.core.Outcome
import com.nearlink.app.domain.model.ConnectionState
import com.nearlink.app.domain.model.FrameType
import com.nearlink.app.domain.model.Peer
import com.nearlink.app.domain.model.ScanState
import com.nearlink.app.domain.model.TransportStatus
import com.nearlink.app.domain.repository.IdentityRepository
import com.nearlink.app.domain.repository.IncomingEnvelope
import com.nearlink.app.domain.repository.PeerRepository
import com.nearlink.app.domain.repository.SettingsRepository
import com.nearlink.app.domain.repository.TransportRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementacion real del transporte de malla NearLink sobre Bluetooth LE.
 *
 * Funciones que antes eran simuladas y ahora son reales:
 *  - Anuncio BLE en segundo plano (baliza) y descubrimiento de nodos.
 *  - Servidor GATT propio + cliente GATT hacia cada nodo.
 *  - Handshake de identidad (intercambio de claves publicas P-256 -> ECDH).
 *  - Fragmentacion segun MTU, ACK de entrega y reintentos.
 *  - Reenvio (relay) de tramas ajenas para extender la malla.
 */
@SuppressLint("MissingPermission")
class NearLinkTransport(
    context: Context,
    private val identityRepository: IdentityRepository,
    private val peerRepository: PeerRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: CoroutineDispatchers,
    private val scope: CoroutineScope,
) : TransportRepository {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)

    private val advertiser = BleAdvertiser(appContext)
    private val scanner = BleScanner(appContext)

    private val gattServer = GattServer(
        context = appContext,
        identityProvider = { identityBytes() },
        onPacket = { address, bytes -> onTransportPacket(address, bytes) },
        onClientChanged = { address, connected -> onClientChanged(address, connected) },
    )

    /** Clientes GATT salientes por direccion MAC. */
    private val clients = ConcurrentHashMap<String, GattClient>()

    /** ACKs pendientes por id de mensaje. */
    private val pendingAcks = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()

    /** Ids de mensaje ya reenviados (evita bucles en la malla). */
    private val relayedIds = LinkedHashSet<String>(64)

    /** Buffer de mensajes multiparte (archivos grandes) por nodo+mensaje. */
    private val incomingParts = ConcurrentHashMap<String, PartsBuffer>()

    /**
     * Tramas recibidas, listas para que las consume el buzon.
     *
     * Se emite con `emit()` (suspende) en lugar de `tryEmit()`: con el buffer
     * lleno `tryEmit` devuelve `false` y la trama se descartaba en silencio
     * porque nadie miraba el valor devuelto. El mensaje "intentaba llegar" y
     * no aparecia nunca en el otro movil.
     */
    private val _incoming = MutableSharedFlow<IncomingEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val _status = MutableStateFlow(TransportStatus())
    private val _scanState = MutableStateFlow(ScanState.IDLE)

    private var scanStopJob: Job? = null

    private var scanErrorJob: Job? = null

    private var advertiserObserver: Job? = null

    override val status: StateFlow<TransportStatus> = _status.asStateFlow()
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private fun identityBytes(): ByteArray = runCatching {
        IdentityPayload.encode(
            displayName = runBlockingName(),
            publicKey = runCatching { runBlockingPublicKey() }.getOrElse { ByteArray(0) },
        )
    }.getOrDefault(ByteArray(0))

    /**
     * El proveedor de identidad de GATT se invoca desde un binder thread, asi
     * que se cachea la identidad para no bloquear.
     */
    @Volatile
    private var cachedName: String = "Nodo NearLink"

    @Volatile
    private var cachedPublicKey: ByteArray = ByteArray(0)

    private fun runBlockingName(): String = cachedName

    private fun runBlockingPublicKey(): ByteArray = cachedPublicKey

    suspend fun refreshIdentityCache() {
        cachedName = settingsRepository.current().displayName
        cachedPublicKey = identityRepository.publicKeyBytes()
    }

    // ------------------------------------------------------------- Transport

    override fun observeIncoming(): Flow<IncomingEnvelope> = _incoming

    override fun isBluetoothEnabled(): Boolean = bluetoothManager?.adapter?.isEnabled == true

    override fun localIdentityBytes(): ByteArray = identityBytes()

    override suspend fun start(): Outcome<Unit> = withContext(dispatchers.io) {
        refreshIdentityCache()
        if (!isBluetoothEnabled()) {
            _status.value = _status.value.copy(bluetoothEnabled = false, advertising = false, message = "Bluetooth apagado")
            return@withContext Outcome.Failure(message = "Bluetooth apagado")
        }
        val serverStarted = gattServer.start()
        if (!serverStarted) {
            return@withContext Outcome.Failure(message = "No se pudo abrir el servidor GATT")
        }
        val relay = settingsRepository.current().relayEnabled
        advertiser.start(
            nodeId = identityRepository.advertiseId(),
            relay = relay,
        )
        observeAdvertiserState()
        refreshStatus()
        Outcome.Success(Unit)
    }

    /**
     * Publica el estado real de la baliza: el resultado de startAdvertising
     * llega de forma asincrona en el callback, asi que se observa su estado
     * para reflejar en la UI (y en la notificacion) si de verdad se anuncia.
     */
    private fun observeAdvertiserState() {
        advertiserObserver?.cancel()
        advertiserObserver = scope.launch(dispatchers.io) {
            combine(advertiser.isAdvertising, advertiser.lastError) { advertising, error ->
                advertising to error
            }.collect { (advertising, error) ->
                _status.value = _status.value.copy(
                    advertising = advertising,
                    message = error ?: _status.value.message,
                )
            }
        }
    }

    override suspend fun stop() {
        withContext(dispatchers.io) {
            advertiserObserver?.cancel()
            advertiserObserver = null
            scanner.stop()
            advertiser.stop()
            clients.values.forEach { it.close() }
            clients.clear()
            gattServer.stop()
            _scanState.value = ScanState.IDLE
            refreshStatus()
            pendingAcks.values.forEach { it.cancel() }
            pendingAcks.clear()
        }
    }

    override suspend fun startScan(): Outcome<Unit> {
        return withContext(dispatchers.io) {
            if (!isBluetoothEnabled()) {
                _scanState.value = ScanState.UNAVAILABLE
                val message = "Bluetooth apagado"
                _status.value = _status.value.copy(message = message)
                return@withContext Outcome.Failure(message = message)
            }
            _scanState.value = ScanState.SCANNING
            val started = scanner.start { node ->
                scope.launch(dispatchers.io) { registerDiscovered(node) }
            }
            if (!started) {
                _scanState.value = ScanState.ERROR
                val message = scanner.lastError.value ?: "No se pudo iniciar el escaneo"
                _status.value = _status.value.copy(message = message)
                return@withContext Outcome.Failure(message = message)
            }
            scanStopJob?.cancel()
            scanStopJob = scope.launch {
                delay(SCAN_WINDOW_MS)
                stopScan()
            }
            observeScanErrors()
            Outcome.Success(Unit)
        }
    }

    /**
     * El sistema puede rechazar el escaneo de forma asincrona (onScanFailed).
     * Se observa el error para detener el indicador y avisar del motivo.
     */
    private fun observeScanErrors() {
        scanErrorJob?.cancel()
        scanErrorJob = scope.launch(dispatchers.io) {
            scanner.lastError.collect { error ->
                if (error != null && _scanState.value == ScanState.SCANNING) {
                    _scanState.value = ScanState.ERROR
                    _status.value = _status.value.copy(message = error)
                }
            }
        }
    }

    override suspend fun stopScan() {
        withContext(dispatchers.io) {
            scanStopJob?.cancel()
            scanStopJob = null
            scanErrorJob?.cancel()
            scanErrorJob = null
            scanner.stop()
            if (_scanState.value != ScanState.ERROR) _scanState.value = ScanState.IDLE
        }
    }

    override suspend fun connect(peerId: String): Outcome<Unit> = withContext(dispatchers.io) {
        if (!isBluetoothEnabled()) return@withContext Outcome.Failure(message = "Bluetooth apagado")
        val existing = clients[peerId]
        if (existing != null && existing.isReady()) return@withContext Outcome.Success(Unit)

        val adapter = bluetoothManager?.adapter ?: return@withContext Outcome.Failure(message = "Sin adaptador Bluetooth")
        val device = runCatching { adapter.getRemoteDevice(peerId) }.getOrNull()
            ?: return@withContext Outcome.Failure(message = "Direccion no valida: $peerId")

        peerRepository.updateConnectionState(peerId, true)
        val deferred = CompletableDeferred<Boolean>()
        val client = GattClient(
            context = appContext,
            device = device,
            listener = object : GattClient.Listener {
                override fun onConnected(address: String, mtu: Int) {
                    scope.launch(dispatchers.io) {
                        peerRepository.upsert(
                            peerRepository.find(address)?.copy(
                                connectionState = ConnectionState.CONNECTED,
                                lastSeen = System.currentTimeMillis(),
                            ) ?: Peer(
                                id = address,
                                name = "Nodo ${address.takeLast(5)}",
                                address = address,
                                rssi = -70,
                                lastSeen = System.currentTimeMillis(),
                                connectionState = ConnectionState.CONNECTED,
                            ),
                        )
                        // Handshake: leemos su identidad y le enviamos la nuestra.
                        sendHello(address)
                        deferred.complete(true)
                    }
                }

                override fun onDisconnected(address: String) {
                    clients.remove(address)
                    scope.launch(dispatchers.io) {
                        peerRepository.find(address)?.let {
                            peerRepository.upsert(it.copy(connectionState = ConnectionState.DISCONNECTED))
                        }
                    }
                    deferred.complete(false)
                }

                override fun onFailure(address: String, message: String) {
                    clients.remove(address)
                    scope.launch(dispatchers.io) {
                        peerRepository.find(address)?.let {
                            peerRepository.upsert(it.copy(connectionState = ConnectionState.FAILED))
                        }
                    }
                    deferred.complete(false)
                }

                override fun onPacket(address: String, bytes: ByteArray) {
                    onTransportPacket(address, bytes)
                }

                override fun onWriteComplete(address: String, success: Boolean) {
                    if (!success) {
                        scope.launch(dispatchers.io) {
                            peerRepository.find(address)?.let {
                                peerRepository.upsert(it.copy(connectionState = ConnectionState.FAILED))
                            }
                        }
                    }
                }

                override fun onIdentity(address: String, bytes: ByteArray?) {
                    if (bytes != null) {
                        scope.launch(dispatchers.io) { completeHandshake(address, bytes) }
                    }
                }
            },
        )
        clients[peerId] = client
        if (!client.connect()) {
            clients.remove(peerId)
            peerRepository.updateConnectionState(peerId, false)
            return@withContext Outcome.Failure(message = "No se pudo iniciar la conexion GATT")
        }
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() } ?: false
        refreshStatus()
        if (ok) Outcome.Success(Unit) else Outcome.Failure(message = "Tiempo de espera agotado al conectar")
    }

    override suspend fun disconnect(peerId: String) {
        withContext(dispatchers.io) {
            clients.remove(peerId)?.close()
            peerRepository.updateConnectionState(peerId, false)
            refreshStatus()
        }
    }

    /**
     * Difusión de malla: envía la trama a todos los nodos enlazados (clientes
     * salientes + clientes entrantes del servidor GATT). Los nodos con el modo
     * repetidor activo la reenviarán al resto de la malla.
     */
    override suspend fun broadcast(payload: ByteArray): Outcome<Unit> = withContext(dispatchers.io) {
        val frame = Packet.encode(
            type = Packet.TYPE_GROUP,
            messageId = UUID.randomUUID(),
            originId = identityRepository.nodeId(),
            payload = payload,
            ttl = Packet.MAX_TTL,
        )
        val targets = (gattServer.connectedAddresses() + clients.keys).distinct()
        var sent = 0
        targets.forEach { address -> if (deliver(address, frame)) sent++ }
        if (sent > 0) Outcome.Success(Unit) else Outcome.Failure(message = "No hay nodos enlazados para difundir")
    }

    /** Enlaza con hasta [limit] nodos conocidos que estén desconectados (en paralelo). */
    override suspend fun connectAllKnown(limit: Int): Int = withContext(dispatchers.io) {
        val candidates = peerRepository.all()
            .filter { it.connectionState != ConnectionState.CONNECTED && !it.blocked }
            .sortedByDescending { it.lastSeen }
            .take(limit)
        if (candidates.isEmpty()) return@withContext 0
        val results = coroutineScope {
            candidates.map { peer -> async { runCatching { connect(peer.id) }.getOrNull() } }.awaitAll()
        }
        results.count { it is Outcome.Success }
    }

    override suspend fun send(
        peerId: String,
        type: FrameType,
        payload: ByteArray,
        requireAck: Boolean,
    ): Outcome<Unit> = withContext(dispatchers.io) {
        if (clients[peerId]?.isReady() != true && gattServer.isConnected(peerId).not()) {
            when (val connected = connect(peerId)) {
                is Outcome.Failure -> return@withContext connected
                is Outcome.Success -> Unit
            }
        }
        val messageId = UUID.randomUUID()
        val ackDeferred = if (requireAck) {
            CompletableDeferred<Boolean>().also { pendingAcks[messageId] = it }
        } else {
            null
        }

        val originId = identityRepository.nodeId()
        val baseFlags = if (requireAck) Packet.FLAG_REQUIRE_ACK else 0
        val parts = splitPayload(payload)
        val delivered = if (parts.size == 1) {
            deliver(
                peerId,
                Packet.encode(
                    type = type.toPacketType(),
                    messageId = messageId,
                    originId = originId,
                    payload = payload,
                    ttl = Packet.MAX_TTL,
                    flags = baseFlags,
                ),
            )
        } else {
            sendMultipart(peerId, type, messageId, originId, parts, baseFlags)
        }
        if (!delivered) {
            pendingAcks.remove(messageId)
            return@withContext Outcome.Failure(message = "No hay enlace con el nodo")
        }
        if (ackDeferred == null) return@withContext Outcome.Success(Unit)

        val confirmed = withTimeoutOrNull(ACK_TIMEOUT_MS) { ackDeferred.await() } ?: false
        pendingAcks.remove(messageId)
        if (confirmed) Outcome.Success(Unit) else Outcome.Failure(message = "El nodo no confirmo la recepcion")
    }

    /**
     * Reintenta el enlace con los nodos vistos mas recientemente para que la
     * cola de mensajes pendientes pueda vaciarse. Se limita el numero y la
     * antiguedad para no consumir bateria.
     */
    override suspend fun flushPending() {
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            peerRepository.all()
                .filter {
                    it.connectionState == ConnectionState.DISCONNECTED &&
                        now - it.lastSeen < RECONNECT_WINDOW_MS
                }
                .sortedByDescending { it.lastSeen }
                .take(MAX_AUTO_RECONNECT)
                .forEach { runCatching { connect(it.id) } }
        }
    }

    // -------------------------------------------------------------- privado

    /** Divide un payload demasiado grande para una sola trama. */
    private fun splitPayload(payload: ByteArray): List<ByteArray> {
        if (payload.size <= MAX_FRAME_PAYLOAD) return listOf(payload)
        val parts = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val length = MAX_FRAME_PAYLOAD.coerceAtMost(payload.size - offset)
            parts.add(payload.copyOfRange(offset, offset + length))
            offset += length
        }
        return parts
    }

    /** Envia cada parte como una trama independiente con el mismo id. */
    private suspend fun sendMultipart(
        peerId: String,
        type: FrameType,
        messageId: UUID,
        originId: String,
        parts: List<ByteArray>,
        baseFlags: Int,
    ): Boolean {
        var ok = true
        parts.forEachIndexed { index, part ->
            val framed = ByteBuffer.allocate(Packet.MULTIPART_HEADER_SIZE + part.size)
                .putShort(index.toShort())
                .putShort(parts.size.toShort())
                .put(part)
                .array()
            val sent = deliver(
                peerId,
                Packet.encode(
                    type = type.toPacketType(),
                    messageId = messageId,
                    originId = originId,
                    payload = framed,
                    ttl = Packet.MAX_TTL,
                    flags = baseFlags or Packet.FLAG_MULTIPART,
                ),
            )
            if (!sent) {
                ok = false
                return@forEachIndexed
            }
            delay(PART_SPACING_MS)
        }
        return ok
    }

    /** Acumula las partes de un mensaje grande; devuelve el payload completo. */
    private fun appendPart(address: String, decoded: DecodedPacket): ByteArray? {
        if (decoded.payload.size < Packet.MULTIPART_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(decoded.payload)
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        if (total <= 0 || index >= total) return null
        val part = decoded.payload.copyOfRange(Packet.MULTIPART_HEADER_SIZE, decoded.payload.size)

        val key = "$address|${decoded.messageId}"
        val entry = incomingParts.getOrPut(key) { PartsBuffer(total) }
        if (entry.total != total) {
            incomingParts.remove(key)
            return null
        }
        if (entry.parts[index] == null) {
            entry.parts[index] = part
            entry.received++
        }
        if (entry.received < entry.total) return null

        incomingParts.remove(key)
        val output = ByteArrayOutputStream()
        entry.parts.forEach { bytes -> output.write(bytes) }
        return output.toByteArray()
    }

    private fun partIndexOf(payload: ByteArray): Int {
        if (payload.size < Packet.MULTIPART_HEADER_SIZE) return -1
        return (ByteBuffer.wrap(payload).short.toInt()) and 0xFFFF
    }

    private class PartsBuffer(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)

        @Volatile
        var received: Int = 0
    }

    /** Envia una trama al peer usando el canal disponible (cliente o servidor). */
    private suspend fun deliver(peerId: String, frame: ByteArray): Boolean {
        val client = clients[peerId]
        if (client != null && client.isReady()) {
            return client.send(frame)
        }
        if (gattServer.isConnected(peerId)) {
            return notifyChunks(peerId, frame)
        }
        return false
    }

    private suspend fun notifyChunks(address: String, frame: ByteArray): Boolean {
        val chunks = Chunker(gattServer.maxChunkPayload(address)).chunk(frame)
        for (chunk in chunks) {
            if (!gattServer.notify(address, chunk)) return false
            delay(NOTIFY_SPACING_MS)
        }
        return true
    }

    private suspend fun sendHello(address: String) {
        clients[address]?.readIdentity()
        val frame = Packet.encode(
            type = Packet.TYPE_HELLO,
            messageId = UUID.randomUUID(),
            originId = identityRepository.nodeId(),
            payload = identityBytes(),
            ttl = 1,
        )
        deliver(address, frame)
    }

    private suspend fun completeHandshake(address: String, identityPayload: ByteArray) {
        val identity = IdentityPayload.decode(identityPayload) ?: return
        val secret = identityRepository.sharedSecret(identity.publicKey)
        if (secret is Outcome.Failure) return
        val fingerprint = identityRepository.fingerprintOf(identity.publicKey)
        val existing = peerRepository.find(address)
        peerRepository.upsert(
            (existing ?: Peer(
                id = address,
                name = identity.name,
                address = address,
                rssi = -70,
                lastSeen = System.currentTimeMillis(),
                connectionState = ConnectionState.CONNECTED,
            )).copy(
                name = identity.name,
                fingerprint = fingerprint,
                publicKey = identity.publicKey,
                connectionState = ConnectionState.CONNECTED,
                lastSeen = System.currentTimeMillis(),
            ),
        )
    }

    private fun onClientChanged(address: String, connected: Boolean) {
        scope.launch(dispatchers.io) {
            val existing = peerRepository.find(address)
            if (existing != null) {
                peerRepository.upsert(
                    existing.copy(
                        connectionState = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                        lastSeen = System.currentTimeMillis(),
                    ),
                )
            }
            refreshStatus()
        }
    }

    /** Recalcula el estado publicado: baliza, nodos enlazados y Bluetooth. */
    private suspend fun refreshStatus(message: String? = null) {
        val connected = gattServer.connectedAddresses().size + clients.count { it.value.isReady() }
        _status.value = _status.value.copy(
            advertising = advertiser.isAdvertising.value,
            connectedPeers = connected,
            bluetoothEnabled = isBluetoothEnabled(),
            message = message ?: _status.value.message,
        )
    }

    private suspend fun registerDiscovered(node: DiscoveredNode) {
        val existing = peerRepository.find(node.address)
        val peer = (existing ?: Peer(
            id = node.address,
            name = node.name?.takeIf { it.isNotBlank() } ?: "Nodo ${node.address.takeLast(5)}",
            address = node.address,
            rssi = node.rssi,
            lastSeen = node.seenAt,
            connectionState = ConnectionState.DISCONNECTED,
        )).copy(
            name = node.name?.takeIf { it.isNotBlank() } ?: existing?.name ?: "Nodo ${node.address.takeLast(5)}",
            rssi = node.rssi,
            lastSeen = node.seenAt,
            relay = node.relay || existing?.relay == true,
        )
        peerRepository.upsert(peer)
    }

    /**
     * Entrada comun de tramas: las que llegan por el servidor GATT y las que
     * llegan por los clientes GATT.
     */
    private fun onTransportPacket(address: String, bytes: ByteArray) {
        val decoded = Packet.decode(bytes) ?: return
        scope.launch(dispatchers.io) {
            // Un archivo grande llega en varias tramas: hay que reensamblarlo
            // antes de poder descifrarlo y guardarlo.
            val payload = if (decoded.flags and Packet.FLAG_MULTIPART != 0) {
                appendPart(address, decoded) ?: return@launch
            } else {
                decoded.payload
            }

            when (decoded.type) {
                Packet.TYPE_HELLO -> {
                    completeHandshake(address, payload)
                    val ackFrame = Packet.encode(
                        type = Packet.TYPE_HELLO_ACK,
                        messageId = UUID.randomUUID(),
                        originId = identityRepository.nodeId(),
                        payload = identityBytes(),
                        ttl = 1,
                    )
                    deliver(address, ackFrame)
                }

                Packet.TYPE_HELLO_ACK -> completeHandshake(address, payload)

                Packet.TYPE_ACK -> pendingAcks.remove(decoded.messageId)?.complete(true)

                else -> {
                    if (decoded.flags and Packet.FLAG_REQUIRE_ACK != 0 && decoded.originId != identityRepository.nodeId()) {
                        val ack = Packet.ack(decoded.messageId, identityRepository.nodeId())
                        deliver(address, ack)
                    }
                    _incoming.emit(
                        IncomingEnvelope(
                            senderId = address,
                            payload = payload,
                            type = decoded.type.toFrameType(),
                            hops = (Packet.MAX_TTL - decoded.ttl).coerceAtLeast(0),
                            originId = decoded.originId,
                            messageId = decoded.messageId.toString(),
                        ),
                    )
                    maybeRelay(address, decoded)
                }
            }
        }
    }

    /** Reenvio de tramas ajenas si el usuario tiene activado el modo relay. */
    private suspend fun maybeRelay(fromAddress: String, decoded: DecodedPacket) {
        val relayEnabled = settingsRepository.current().relayEnabled
        if (!relayEnabled || decoded.ttl <= 1) return
        val key = if (decoded.flags and Packet.FLAG_MULTIPART != 0) {
            "${decoded.messageId}:${partIndexOf(decoded.payload)}"
        } else {
            decoded.messageId.toString()
        }
        synchronized(relayedIds) {
            if (!relayedIds.add(key)) return
            if (relayedIds.size > MAX_RELAY_CACHE) {
                val iterator = relayedIds.iterator()
                iterator.next()
                iterator.remove()
            }
        }
        val forwarded = Packet.encode(
            type = decoded.type,
            messageId = decoded.messageId,
            originId = decoded.originId,
            payload = decoded.payload,
            ttl = decoded.ttl - 1,
            flags = decoded.flags or Packet.FLAG_RELAYED,
        )
        val targets = (gattServer.connectedAddresses() + clients.keys)
            .filter { it != fromAddress && it != decoded.originId }
            .distinct()
        targets.forEach { deliver(it, forwarded) }
    }

    private fun FrameType.toPacketType(): Int = when (this) {
        FrameType.HELLO -> Packet.TYPE_HELLO
        FrameType.HELLO_ACK -> Packet.TYPE_HELLO_ACK
        FrameType.MESSAGE -> Packet.TYPE_MESSAGE
        FrameType.ACK -> Packet.TYPE_ACK
        FrameType.FILE -> Packet.TYPE_FILE
        FrameType.SOS -> Packet.TYPE_SOS
        FrameType.GROUP -> Packet.TYPE_GROUP
    }

    private fun Int.toFrameType(): FrameType = when (this) {
        Packet.TYPE_FILE -> FrameType.FILE
        Packet.TYPE_SOS -> FrameType.SOS
        Packet.TYPE_ACK -> FrameType.ACK
        Packet.TYPE_HELLO -> FrameType.HELLO
        Packet.TYPE_HELLO_ACK -> FrameType.HELLO_ACK
        Packet.TYPE_GROUP -> FrameType.GROUP
        else -> FrameType.MESSAGE
    }

    companion object {
        private const val ACK_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val NOTIFY_SPACING_MS = 20L
        private const val SCAN_WINDOW_MS = 60_000L
        private const val MAX_RELAY_CACHE = 256
        private const val RECONNECT_WINDOW_MS = 30 * 60 * 1000L

        /** Payload maximo por trama (deja margen bajo el limite de 64 KB). */
        private const val MAX_FRAME_PAYLOAD = 60_000
        private const val PART_SPACING_MS = 30L
        private const val MAX_AUTO_RECONNECT = 3
    }
}
