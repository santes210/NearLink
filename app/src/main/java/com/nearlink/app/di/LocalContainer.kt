package com.nearlink.app.di

import androidx.compose.runtime.staticCompositionLocalOf

/** Contenedor de dependencias accesible desde la composicion. */
val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer no proporcionado. Envuelve el contenido con ProvideAppContainer.")
}
