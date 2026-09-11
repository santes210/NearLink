package com.nearlink.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Formateo de fechas/horas para la UI respetando el locale del sistema.
 *
 * java.time esta disponible de forma nativa desde API 26 (nuestro minSdk),
 * asi que no hace falta desugaring.
 */
@Singleton
class TimeFormatter {

    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val mediumDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)

    fun formatDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(mediumDateFormatter)

    /** Etiqueta de separador de dias: HOY / AYER / fecha completa. */
    fun formatDayLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            date == today -> "HOY"
            date == today.minusDays(1) -> "AYER"
            date.year == today.year -> mediumDateFormatter.format(date).uppercase(Locale.getDefault())
            else -> mediumDateFormatter.format(date).uppercase(Locale.getDefault())
        }
    }

    fun isSameDay(first: Long, second: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
    }

    fun formatDuration(epochMillis: Long): String {
        val totalSeconds = (epochMillis / 1000).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    fun today(): LocalDate = LocalDate.now()
}
