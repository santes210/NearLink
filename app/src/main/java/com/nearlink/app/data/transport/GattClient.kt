package com.nearlink.app.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Cliente GATT: nos conectamos al servidor de otro nodo, negociamos MTU,
 * habilitamos notificaciones y escribimos las tramas en cola (BLE no permite
 * mas de una escritura en vuelo).
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(address: String, mtu: Int)
        fun onDisconnected(address: String)
        fun onFailure(address: String, message: String)
        fun onPacket(address: String, bytes: ByteArray)
        fun onWriteComplete(address: String, success: Boolean)
        fun onIdentity(address: String, bytes: ByteArray?)
    }

    private var gatt: BluetoothGatt? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var identityCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var mtu = 23

    @Volatile
    private var ready = false

    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var writing = false

    private val assembler = ChunkAssembler()

    val address: String get() = device.address

    fun connect(): Boolean {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    fun isReady(): Boolean = ready

    /** Encola un payload completo; se fragmenta segun el MTU negociado. */
    fun send(payload: ByteArray): Boolean {
        if (!ready) return false
        val chunker = Chunker(NearLinkBle.maxChunkPayload(mtu))
        chunker.chunk(payload).forEach { writeQueue.add(it) }
        pump()
        return true
    }

    fun readIdentity() {
        val characteristic = identityCharacteristic ?: return
        gatt?.readCharacteristic(characteristic)
    }

    fun close() {
        writeQueue.clear()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        ready = false
    }

    @Synchronized
    private fun pump() {
        if (writing) return
        val chunk = writeQueue.poll() ?: return
        val characteristic = messageCharacteristic ?: return
        val target = gatt ?: return
        writing = true
        val ok = runCatching {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = chunk
            target.writeCharacteristic(characteristic)
        }.getOrDefault(false)
        if (!ok) {
            writing = false
            listener.onWriteComplete(address, false)
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gatt.requestMtu(NearLinkBle.REQUESTED_MTU)
                    } else {
                        listener.onFailure(address, "Error de conexion GATT ($status)")
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    ready = false
                    writeQueue.clear()
                    assembler.reset(address)
                    listener.onDisconnected(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@GattClient.mtu = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onFailure(address, "No se pudieron descubrir servicios ($status)")
                return
            }
            val service = gatt.getService(NearLinkBle.SERVICE_UUID)
            if (service == null) {
                listener.onFailure(address, "El nodo no expone el servicio NearLink")
                return
            }
            messageCharacteristic = service.getCharacteristic(NearLinkBle.MESSAGE_CHARACTERISTIC)
            identityCharacteristic = service.getCharacteristic(NearLinkBle.IDENTITY_CHARACTERISTIC)
            val message = messageCharacteristic
            if (message == null) {
                listener.onFailure(address, "Caracteristica de mensajes ausente")
                return
            }
            gatt.setCharacteristicNotification(message, true)
            val descriptor = message.getDescriptor(NearLinkBle.CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                finishSetup()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            finishSetup()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writing = false
            listener.onWriteComplete(address, status == BluetoothGatt.GATT_SUCCESS)
            pump()
        }

        // API 33+ entrega el valor como parametro.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncoming(value)
        }

        // API < 33 hay que leer el valor de la caracteristica.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleIncoming(characteristic.value ?: return)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleIdentityRead(characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleIdentityRead(characteristic, characteristic.value ?: ByteArray(0), status)
        }
    }

    private fun handleIncoming(value: ByteArray) {
        val complete = assembler.add(address, value) ?: return
        listener.onPacket(address, complete)
    }

    private fun handleIdentityRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (characteristic.uuid == NearLinkBle.IDENTITY_CHARACTERISTIC) {
            listener.onIdentity(address, if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }

    private fun finishSetup() {
        ready = true
        listener.onConnected(address, mtu)
        pump()
    }

    companion object {
        val SERVICE: UUID = NearLinkBle.SERVICE_UUID
    }
}
