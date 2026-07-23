package com.nearlink.app.data.security

import android.content.Context
import android.util.Base64
import com.nearlink.app.transport.ContactEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Directorio de contactos: huella (fingerprint) -> llave pública X25519.
 *
 * Se puebla al conectar con otros nodos (KeyExchange) y mediante gossip (Contacts),
 * de modo que la app aprende las llaves públicas de nodos que están varios saltos
 * lejos y puede cifrarles mensajes E2E para que la malla los reenvíe.
 * Persistente en SharedPreferences.
 */
class ContactStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val map: MutableMap<String, ByteArray> = load()

    fun get(fp: String): ByteArray? = map[fp]
    fun contains(fp: String): Boolean = map.containsKey(fp)
    fun all(): Map<String, ByteArray> = map.toMap()
    fun entries(): List<ContactEntry> = map.map { ContactEntry(it.key, it.value) }

    fun put(fp: String, pubKey: ByteArray) {
        if (map[fp]?.contentEquals(pubKey) != true) {
            map[fp] = pubKey
            persist()
        }
    }

    fun merge(entries: List<ContactEntry>): Boolean {
        var changed = false
        for (e in entries) {
            if (map[e.fp]?.contentEquals(e.pubKey) != true) {
                map[e.fp] = e.pubKey
                changed = true
            }
        }
        if (changed) persist()
        return changed
    }

    private fun persist() {
        val arr = JSONArray()
        for ((fp, key) in map) {
            arr.put(JSONObject().put("fp", fp).put("pub", Base64.encodeToString(key, Base64.NO_WRAP)))
        }
        prefs.edit().putString(KEY_DIR, arr.toString()).apply()
    }

    private fun load(): MutableMap<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        prefs.getString(KEY_DIR, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    m[e.getString("fp")] = Base64.decode(e.getString("pub"), Base64.NO_WRAP)
                }
            } catch (_: Exception) {
            }
        }
        return m
    }

    companion object {
        private const val PREFS = "nearlink_contacts"
        private const val KEY_DIR = "dir"
    }
}
