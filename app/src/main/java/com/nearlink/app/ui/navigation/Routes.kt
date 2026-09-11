package com.nearlink.app.ui.navigation

/** Rutas del grafo de navegacion. */
object Routes {
    const val HOME = "home"
    const val RADAR = "radar"
    const val SETTINGS = "settings"
    const val CHAT = "chat"
    const val CHAT_ARG_PEER = "peerId"
    const val CHAT_PATTERN = "$CHAT/{$CHAT_ARG_PEER}"

    fun chat(peerId: String): String = "$CHAT/$peerId"

    /** Destinos en los que se muestra la barra de navegacion inferior. */
    fun isTopLevel(route: String?): Boolean =
        route == HOME || route == RADAR || route == SETTINGS
}
