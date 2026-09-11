package com.nearlink.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.nearlink.app.NearLinkApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene viva la malla: anuncio BLE, servidor
 * GATT y buzon de entrada.
 *
 * Se arranca desde la Activity (Android 12+ prohibe lanzar servicios en primer
 * plano desde segundo plano) y solo despues de conceder los permisos.
 */
class NearLinkForegroundService : Service() {

    /**
     * Scope del servicio. El [CoroutineExceptionHandler] es imprescindible:
     * aqui corren el transporte y el buzon de la malla, y una excepcion sin
     * capturar (por ejemplo del Keystore o de la pila BLE) mataba el proceso
     * entero, que es lo que el usuario veia como "la app se cierra sola".
     */
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Excepcion no controlada en el servicio de malla", throwable)
            },
    )
    private var maintenanceJob: Job? = null
    private var statusJob: Job? = null

    private val container by lazy { (applicationContext as NearLinkApplication).container }

    override fun onCreate() {
        super.onCreate()
        NearLinkNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NearLinkNotifications.ACTION_STOP -> {
                stopMesh()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SCAN -> scope.launch { container.transport.startScan() }
            ACTION_RETRY -> scope.launch { container.retryPendingMessages() }
            else -> startMesh()
        }
        return START_STICKY
    }

    private fun startMesh() {
        startForeground(
            NearLinkNotifications.SERVICE_NOTIFICATION_ID,
            NearLinkNotifications.serviceNotification(this, 0, false),
        )
        scope.launch {
            // El buzon se suscribe ANTES de encender el transporte. Al reves
            // existia una ventana en la que el transporte ya emitia tramas y
            // todavia no habia colector: los mensajes entrantes se descartaban.
            container.meshInbox.start()
            runCatching { container.transport.start() }
        }
        statusJob?.cancel()
        statusJob = scope.launch {
            container.transport.status.collectLatest { status ->
                // connectedPeers ya viene calculado en el propio estado del
                // transporte; releer todos los peers de la BD en cada cambio de
                // estado solo cargaba el hilo de la base de datos.
                val notification = NearLinkNotifications.serviceNotification(
                    context = this@NearLinkForegroundService,
                    peers = status.connectedPeers,
                    advertising = status.advertising,
                )
                runCatching {
                    NearLinkNotifications.createChannels(this@NearLinkForegroundService)
                    startForeground(NearLinkNotifications.SERVICE_NOTIFICATION_ID, notification)
                }.onFailure { Log.w(TAG, "No se pudo actualizar la notificacion del servicio", it) }
            }
        }
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                runCatching { container.purgeExpiredMessages() }
                runCatching { container.retryPendingMessages() }
                // Curación de la malla: reenlaza con nodos conocidos que estén
                // desconectados (necesario para que los grupos sigan llegando).
                runCatching { container.transport.connectAllKnown() }
            }
        }
    }

    private fun stopMesh() {
        statusJob?.cancel()
        maintenanceJob?.cancel()
        scope.launch {
            runCatching { container.transport.stop() }
            container.meshInbox.stop()
        }
    }

    override fun onDestroy() {
        stopMesh()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SCAN = "com.nearlink.app.action.SCAN"
        const val ACTION_RETRY = "com.nearlink.app.action.RETRY"
        private const val TAG = "NearLink"
        private const val MAINTENANCE_INTERVAL_MS = 60_000L
    }
}
