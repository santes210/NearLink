package com.nearlink.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupKeysTest {

    @Test
    fun `channelId es deterministico y tiene 16 bytes`() {
        val a = GroupKeys.channelId("CodigoCompartido-2026")
        val b = GroupKeys.channelId("CodigoCompartido-2026")
        assertArrayEquals(a, b)
        assertEquals(16, a.size)
    }

    @Test
    fun `codigos distintos producen canales distintos`() {
        val a = GroupKeys.channelId("grupo-uno")
        val b = GroupKeys.channelId("grupo-dos")
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `hexOf y hexToBytes son inversas`() {
        val bytes = ByteArray(16) { (it * 7 + 3).toByte() }
        val hex = GroupKeys.hexOf(bytes)
        assertEquals(32, hex.length)
        assertArrayEquals(bytes, GroupKeys.hexToBytes(hex))
    }

    @Test
    fun `messageKey deriva 32 bytes y depende de la salt`() {
        val groupKey = ByteArray(32) { 1 }
        val saltA = ByteArray(16) { 2 }
        val saltB = ByteArray(16) { 3 }

        val keyA = GroupKeys.messageKey(groupKey, saltA)
        val keyA2 = GroupKeys.messageKey(groupKey, saltA)
        val keyB = GroupKeys.messageKey(groupKey, saltB)

        assertEquals(32, keyA.size)
        assertArrayEquals(keyA, keyA2)
        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `groupKey es deterministico y tiene 32 bytes`() {
        val code = "FraseLargaDePrueba-1234"
        val k1 = GroupKeys.groupKey(code)
        val k2 = GroupKeys.groupKey(code)
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k2)
        assertTrue(k1.any { it != 0.toByte() })
    }
}
