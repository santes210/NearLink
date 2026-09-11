package com.nearlink.app.data.transport

import java.util.UUID

/** Identificadores del servicio NearLink sobre BLE. */
object NearLinkBle {

    /**
     * Servicio propietario NearLink.
     *
     * IMPORTANTE: se usa un UUID de 16 bits (en su forma base de 128 bits) a
     * proposito. El anuncio BLE legado tiene un limite de 31 bytes, y si el
     * servicio se anuncia como UUID de 128 bits aleatorios, el paquete de
     * anuncio (lista de servicios + service data) suma 41 bytes y el sistema
     * rechaza la baliza con ADVERTISE_FAILED_DATA_TOO_LARGE. Con el UUID corto
     * la baliza ocupa ~13 bytes y cabe con holgura. Las caracteristicas de
     * GATT siguen siendo internas y no viajan en el anuncio.
     */
    val SERVICE_UUID: UUID = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb")

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
