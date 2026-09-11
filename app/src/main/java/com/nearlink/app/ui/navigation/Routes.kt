package com.nearlink.app.ui.navigation

/** Rutas del grafo de navegacion. */
object Routes {
    const val HOME = "home"
    const val RADAR = "radar"
    const val SETTINGS = "settings"
    const val GROUPS = "groups"
    const val CHAT = "chat"
    const val CHAT_ARG_PEER = "peerId"
    const val CHAT_PATTERN = "$CHAT/{$CHAT_ARG_PEER}"
    const val GROUP_CHAT = "group_chat"
    const val GROUP_CHAT_ARG = "channelId"
    const val GROUP_CHAT_PATTERN = "$GROUP_CHAT/{$GROUP_CHAT_ARG}"

    fun chat(peerId: String): String = "$CHAT/$peerId"

    fun groupChat(channelId: String): String = "$GROUP_CHAT/$channelId"

    /** Destinos en los que se muestra la barra de navegacion inferior. */
    fun isTopLevel(route: String?): Boolean =
        route == HOME || route == RADAR || route == SETTINGS || route == GROUPS
}
