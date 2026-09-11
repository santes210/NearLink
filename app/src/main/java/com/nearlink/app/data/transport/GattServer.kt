package com.nearlink.app.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Servidor GATT: exponemos el servicio NearLink para que otros nodos puedan
 * conectarse a nosotros y enviarnos tramas (y para poder responderles).
 *
 * - Caracteristica MESSAGE: escritura (con confirmacion) + notificaciones.
 * - Caracteristica IDENTITY: lectura de la clave publica del nodo.
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val identityProvider: () -> ByteArray,
    private val onPacket: (deviceAddress: String, bytes: ByteArray) -> Unit,
    private val onClientChanged: (deviceAddress: String, connected: Boolean) -> Unit,
) {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private val assembler = ChunkAssembler()
    private val clients = ConcurrentHashMap<String, ClientState>()

    private val messageCharacteristic = BluetoothGattCharacteristic(
        NearLinkBle.MESSAGE_CHARACTERISTIC,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    private val identityCharacteristic = BluetoothGattCharacteristic(
        NearLinkBle.IDENTITY_CHARACTERISTIC,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )

    private class ClientState {
        @Volatile
        var mtu: Int = NearLinkBle.REQUESTED_MTU

        @Volatile
        var notificationsEnabled: Boolean = false
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    clients[device.address] = ClientState()
                    onClientChanged(device.address, true)
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    clients.remove(device.address)
                    assembler.reset(device.address)
                    onClientChanged(device.address, false)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == NearLinkBle.MESSAGE_CHARACTERISTIC) {
                val complete = assembler.add(device.address, value)
                if (complete != null) onPacket(device.address, complete)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == NearLinkBle.IDENTITY_CHARACTERISTIC) {
                val identity = identityProvider()
                val mtu = clients[device.address]?.mtu ?: NearLinkBle.REQUESTED_MTU
                val max = (mtu - 1).coerceAtLeast(20)
                val slice = identity.copyOfRange(offset, (offset + max).coerceAtMost(identity.size))
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == NearLinkBle.CCCD_UUID) {
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                clients[device.address]?.notificationsEnabled = enabled
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            clients[device.address]?.mtu = mtu
        }
    }

    fun start(): Boolean {
        val bluetoothManager = manager ?: return false
        val gattServer = bluetoothManager.openGattServer(context, callback) ?: return false
        server = gattServer

        val service = BluetoothGattService(
            NearLinkBle.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        messageCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                NearLinkBle.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(messageCharacteristic)
        service.addCharacteristic(identityCharacteristic)
        return runCatching { gattServer.addService(service) }.getOrDefault(false)
    }

    fun stop() {
        server?.close()
        server = null
        clients.clear()
    }

    /** Tamano maximo de payload por chunk negociado con el cliente. */
    fun maxChunkPayload(address: String): Int =
        NearLinkBle.maxChunkPayload(clients[address]?.mtu ?: NearLinkBle.REQUESTED_MTU)

    fun connectedAddresses(): Set<String> = clients.keys.toSet()

    fun isConnected(address: String): Boolean = clients.containsKey(address)

    /**
     * Envia un chunk por notificacion. La llamada debe espaciarse desde el
     * transporte (la pila BLE descarta notificaciones si se encolan demasiado
     * rapido).
     *
     * Devuelve false si el par todavia no ha habilitado las notificaciones
     * (escritura del CCCD). Sin esta comprobacion la pila BLE descartaba el
     * chunk en silencio y el emisor daba la trama por entregada: el mensaje
     * "intentaba llegar" y nunca aparecia en el otro movil.
     */
    fun notify(address: String, chunk: ByteArray): Boolean {
        val gatt = server ?: return false
        if (clients[address]?.notificationsEnabled != true) return false
        val device = gatt.connectedDevices.firstOrNull { it.address == address } ?: return false
        return runCatching {
            messageCharacteristic.value = chunk
            gatt.notifyCharacteristicChanged(device, messageCharacteristic, false)
        }.getOrDefault(false)
    }
}
