package com.nearlink.app.data.repository

import com.nearlink.app.core.CoroutineDispatchers
import com.nearlink.app.data.crypto.CryptoManager
import com.nearlink.app.data.local.dao.SettingsDao
import com.nearlink.app.data.local.entity.SettingEntity
import com.nearlink.app.domain.model.ThemeMode
import com.nearlink.app.domain.model.UserSettings
import com.nearlink.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Preferencias persistentes. Se guardan en la misma base de datos Room que el
 * resto de la app (clave/valor) para no anadir otra dependencia.
 */
class SettingsRepositoryImpl(
    private val dao: SettingsDao,
    private val crypto: CryptoManager,
    private val dispatchers: CoroutineDispatchers,
) : SettingsRepository {

    private val mutex = Mutex()

    private object Keys {
        const val DISPLAY_NAME = "display_name"
        const val DEFAULT_TTL = "default_ttl"
        const val RELAY = "relay_enabled"
        const val AUTO_DELETE = "auto_delete"
        const val THEME = "theme"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val WIFI_THRESHOLD = "wifi_threshold_mb"
        const val PAIRING_PIN = "pairing_pin"
        const val PIN_EXPIRES = "pin_expires_at"
    }

    override val settings: Flow<UserSettings> =
        dao.observeAll().map { entities -> entities.toSettings() }

    override suspend fun current(): UserSettings = withContext(dispatchers.io) {
        dao.all().toSettings()
    }

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        withContext(dispatchers.io) {
            mutex.withLock {
                val updated = transform(dao.all().toSettings())
                updated.asEntities().forEach { dao.upsert(it) }
            }
        }
    }

    override suspend fun rotatePairingPin(ttlMillis: Long): String = withContext(dispatchers.io) {
        mutex.withLock {
            val pin = crypto.randomPin(6)
            val expiresAt = System.currentTimeMillis() + ttlMillis
            dao.upsert(SettingEntity(Keys.PAIRING_PIN, pin))
            dao.upsert(SettingEntity(Keys.PIN_EXPIRES, expiresAt.toString()))
            pin
        }
    }

    private fun List<SettingEntity>.toSettings(): UserSettings {
        val map = associate { it.key to it.value }
        val pinExpires = map[Keys.PIN_EXPIRES]?.toLongOrNull() ?: 0L
        val pin = map[Keys.PAIRING_PIN].takeIf { pinExpires > System.currentTimeMillis() } ?: ""
        return UserSettings(
            displayName = map[Keys.DISPLAY_NAME]?.takeIf { it.isNotBlank() }
                ?: UserSettings().displayName,
            defaultTtlSeconds = map[Keys.DEFAULT_TTL]?.toIntOrNull() ?: 0,
            relayEnabled = map[Keys.RELAY]?.toBooleanStrictOrNull() ?: true,
            autoDeleteEnabled = map[Keys.AUTO_DELETE]?.toBooleanStrictOrNull() ?: true,
            themeMode = map[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = map[Keys.DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
            wifiDirectThresholdMb = map[Keys.WIFI_THRESHOLD]?.toIntOrNull() ?: 5,
            pairingPin = pin,
            pinExpiresAt = pinExpires,
        )
    }

    private fun UserSettings.asEntities(): List<SettingEntity> = listOf(
        SettingEntity(Keys.DISPLAY_NAME, displayName),
        SettingEntity(Keys.DEFAULT_TTL, defaultTtlSeconds.toString()),
        SettingEntity(Keys.RELAY, relayEnabled.toString()),
        SettingEntity(Keys.AUTO_DELETE, autoDeleteEnabled.toString()),
        SettingEntity(Keys.THEME, themeMode.name),
        SettingEntity(Keys.DYNAMIC_COLOR, dynamicColor.toString()),
        SettingEntity(Keys.WIFI_THRESHOLD, wifiDirectThresholdMb.toString()),
        SettingEntity(Keys.PAIRING_PIN, pairingPin),
        SettingEntity(Keys.PIN_EXPIRES, pinExpiresAt.toString()),
    )
}
