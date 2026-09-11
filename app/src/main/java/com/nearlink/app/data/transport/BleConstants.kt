package com.nearlink.app.data.transport

import java.util.UUID

/** Identificadores del servicio NearLink sobre BLE. */
object NearLinkBle {

    /** Servicio propietario NearLink. */
    val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-0000-4000-8000-aabbccddeeff")

    /** Caracteristica por la que viajan las tramas (escritura + notificacion). */
    val MESSAGE_CHARACTERISTIC: UUID = UUID.fromString("a1b2c3d4-0001-4000-8000-aabbccddeeff")

    /** Caracteristica de solo lectura con la identidad publica del nodo. */
    val IDENTITY_CHARACTERISTIC: UUID = UUID.fromString("a1b2c3d4-0002-4000-8000-aabbccddeeff")

    /** Descriptor CCCD estandar (habilita notificaciones). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** MTU que se solicita al abrir el enlace (BLE 4.2+). */
    const val REQUESTED_MTU = 517

    /** Cabecera ATT + cabecera de chunk que hay que descontar del MTU. */
    const val ATT_OVERHEAD = 3
    const val CHUNK_HEADER_SIZE = 4

    fun maxChunkPayload(mtu: Int): Int =
        (mtu - ATT_OVERHEAD - CHUNK_HEADER_SIZE).coerceIn(20, 512)
}
