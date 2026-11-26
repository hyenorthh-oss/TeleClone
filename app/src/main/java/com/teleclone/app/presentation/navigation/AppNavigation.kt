package com.teleclone.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.teleclone.app.data.tdlib.TdLibManager
import com.teleclone.app.domain.model.AuthState
import com.teleclone.app.presentation.screens.auth.AuthScreen
import com.teleclone.app.presentation.screens.chat.ChatScreen
import com.teleclone.app.presentation.screens.chats.ChatsScreen
import com.teleclone.app.presentation.screens.settings.SettingsScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val authState by TdLibManager.authState.collectAsState()
    val startDestination = remember(authState) {
        if (authState is AuthState.Ready) Screen.Chats.route else Screen.Auth.route
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Auth.route) {
            AuthScreen(
                onAuthenticated = {
                    navController.navigate(Screen.Chats.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Chats.route) {
            ChatsScreen(
                onChatClick = { chatId ->
                    navController.navigate(Screen.Chat.createRoute(chatId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong("chatId") ?: return@composable
            ChatScreen(
                chatId = chatId,
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
