package com.nearlink.app.permissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permisos que NearLink necesita segun la version de Android.
 *
 * Android 12 (API 31) cambio el modelo: BLUETOOTH_SCAN / BLUETOOTH_CONNECT /
 * BLUETOOTH_ADVERTISE son permisos peligrosos que hay que pedir en ejecucion.
 * Android 13 (API 33) anadio POST_NOTIFICATIONS y NEARBY_WIFI_DEVICES.
 */
object NearLinkPermissions {

    /**
     * Permisos imprescindibles para que la malla funcione. Si falta uno de
     * estos, la app no puede descubrir ni enlazar nodos.
     */
    fun blocking(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Permisos opcionales: mejoran la experiencia (notificaciones, Wi-Fi
     * Direct) pero NO bloquean el radar ni la malla si se rechazan.
     */
    fun optional(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        else -> emptyList()
    }

    /** Todo lo que se pide en la puerta de permisos (bloqueante + opcional). */
    fun required(): List<String> = blocking() + optional()

    /** Permiso para grabar notas de voz (se pide solo al pulsar el micro). */
    fun audio(): String = Manifest.permission.RECORD_AUDIO

    /** Permisos de lectura de ficheros para adjuntos. */
    fun storage(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )

        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Solo los permisos bloqueantes que faltan (los opcionales se ignoran). */
    fun missing(context: Context, permissions: List<String> = blocking()): List<String> =
        permissions.filter { !isGranted(context, it) }

    /** True si la malla puede funcionar (permisos bloqueantes concedidos). */
    fun hasAll(context: Context, permissions: List<String> = blocking()): Boolean =
        missing(context, permissions).isEmpty()

    fun hasBluetooth(context: Context): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter?.isEnabled == true
    }

    fun hasBluetoothHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}
