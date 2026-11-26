package com.teleclone.app.presentation.navigation

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")
    data object Chats : Screen("chats")
    data object Chat : Screen("chat/{chatId}") {
        fun createRoute(chatId: Long) = "chat/$chatId"
    }
    data object Settings : Screen("settings")
}
