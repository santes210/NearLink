package com.nearlink.app.data.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Contrato de enrutado por origen de la malla.
 *
 * La cabecera de trama lleva el nodeId del emisor original (prefijo de 6 bytes
 * del SHA-256 de su clave publica, con la forma "AA:BB:CC:DD:EE:FF") en un
 * campo de exactamente 6 bytes. `NearLinkTransport` usa ese valor como clave de
 * su indice nodeId -> direccion MAC para saber con que par descifrar una trama
 * que llego a traves de un repetidor.
 *
 * Si el ida y vuelta por ese campo se rompiera (otro numero de bytes, hex en
 * minusculas, un separador distinto), el indice no encontraria nunca la entrada
 * y los mensajes 1:1 reenviados volverian a descartarse en silencio. Estos
 * tests fijan ese contrato.
 */
class OriginRoutingTest {

    /**
     * La forma exacta que produce `IdentityRepositoryImpl.nodeIdOf`:
     * `sha256(clavePublica).take(6).joinToString(":") { "%02X".format(it) }`,
     * que es literalmente lo que hace `Packet.bytesToMac` con los 6 primeros
     * bytes. Se reutiliza el codigo del repositorio a proposito: si las dos
     * formulas se separaran, el indice nodeId -> MAC dejaria de resolver.
     */
    private fun nodeIdOf(sha256OfPublicKey: ByteArray): String = Packet.bytesToMac(sha256OfPublicKey)

    @Test
    fun `el nodeId sobrevive intacto al campo de origen de la cabecera`() {
        val sha = ByteArray(32) { index -> (index * 37 + 11).toByte() }
        val nodeId = nodeIdOf(sha)

        val frame = Packet.encode(
            type = Packet.TYPE_MESSAGE,
            messageId = UUID.randomUUID(),
            originId = nodeId,
            payload = "hola".toByteArray(),
        )
        val decoded = Packet.decode(frame) ?: error("la trama deberia decodificar")

        assertEquals("el receptor debe poder buscar este nodeId en su indice", nodeId, decoded.originId)
    }

    @Test
    fun `el mismo emisor produce el mismo nodeId en tramas distintas`() {
        val sha = ByteArray(32) { index -> (index * 13 - 5).toByte() }

        val primero = Packet.decode(
            Packet.encode(Packet.TYPE_MESSAGE, UUID.randomUUID(), nodeIdOf(sha), "uno".toByteArray()),
        ) ?: error("primera trama corrupta")
        val segundo = Packet.decode(
            Packet.encode(Packet.TYPE_FILE, UUID.randomUUID(), nodeIdOf(sha), "dos".toByteArray()),
        ) ?: error("segunda trama corrupta")

        assertEquals(primero.originId, segundo.originId)
    }

    @Test
    fun `dos emisores distintos no comparten nodeId`() {
        val emisorA = ByteArray(32) { it.toByte() }
        val emisorB = ByteArray(32) { (it + 1).toByte() }

        assertNotEquals(nodeIdOf(emisorA), nodeIdOf(emisorB))
    }

    @Test
    fun `el nodeId usa hex en mayusculas y dos digitos por byte`() {
        val sha = ByteArray(32) { 0x0A }

        assertEquals("0A:0A:0A:0A:0A:0A", nodeIdOf(sha))
    }

    @Test
    fun `el relay no altera el origen de la trama reenviada`() {
        val sha = ByteArray(32) { index -> (index * 7).toByte() }
        val originId = nodeIdOf(sha)

        val original = Packet.encode(
            type = Packet.TYPE_MESSAGE,
            messageId = UUID.randomUUID(),
            originId = originId,
            payload = "salto 0".toByteArray(),
            ttl = Packet.MAX_TTL,
        )
        val decoded = Packet.decode(original) ?: error("trama original corrupta")

        // Lo que hace NearLinkTransport.maybeRelay: reencuadra con ttl - 1 y el
        // flag RELAYED, conservando messageId y originId.
        val relayed = Packet.decode(
            Packet.encode(
                type = decoded.type,
                messageId = decoded.messageId,
                originId = decoded.originId,
                payload = decoded.payload,
                ttl = decoded.ttl - 1,
                flags = decoded.flags or Packet.FLAG_RELAYED,
            ),
        ) ?: error("trama reenviada corrupta")

        assertEquals(originId, relayed.originId)
        assertEquals(decoded.ttl - 1, relayed.ttl)
        assertTrue("el flag RELAYED debe conservarse", relayed.flags and Packet.FLAG_RELAYED != 0)
    }
}
