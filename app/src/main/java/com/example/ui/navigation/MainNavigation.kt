package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatListScreen
import com.example.ui.screens.ChatViewModel
import com.example.ui.screens.DiscoveryScreen

object Routes {
    const val LIST = "list"
    const val DISCOVERY = "discovery"
    const val DETAIL = "chat_detail"
}

@Composable
fun MainAppLayer(viewModel: ChatViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route?.substringBefore("/")

    Scaffold(
        bottomBar = {
            if (currentRoute == Routes.LIST || currentRoute == Routes.DISCOVERY) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.LIST,
                        onClick = {
                            navController.navigate(Routes.LIST) {
                                popUpTo(Routes.LIST) { inclusive = true }
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Messages") },
                        label = { Text("Messages") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.DISCOVERY,
                        onClick = {
                            navController.navigate(Routes.DISCOVERY) {
                                popUpTo(navController.graph.startDestinationId)
                            }
                        },
                        icon = { Icon(Icons.Filled.Group, contentDescription = "Nodes") },
                        label = { Text("Nodes") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { /* Profile */ },
                        icon = { Icon(Icons.Filled.Person, contentDescription = "Profile") },
                        label = { Text("Profile") }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding())) {
            NavHost(navController = navController, startDestination = Routes.LIST) {
                composable(Routes.LIST) {
                    ChatListScreen(
                        viewModel = viewModel,
                        onNavigateToDiscovery = { navController.navigate(Routes.DISCOVERY) },
                        onNavigateToChat = { nodeId -> navController.navigate("${Routes.DETAIL}/$nodeId") }
                    )
                }
                composable(Routes.DISCOVERY) {
                    DiscoveryScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.navigateUp() },
                        onNavigateToChat = { nodeId -> navController.navigate("${Routes.DETAIL}/$nodeId") }
                    )
                }
                composable("${Routes.DETAIL}/{nodeId}") { backStackEntry ->
                    val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
                    ChatDetailScreen(
                        nodeId = nodeId,
                        viewModel = viewModel,
                        onNavigateBack = { navController.navigateUp() }
                    )
                }
            }
        }
    }
}
