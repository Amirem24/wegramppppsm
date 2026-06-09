package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AppDatabase
import com.example.data.repository.ChatRepository
import com.example.nearby.NearbyManager
import com.example.ui.components.PermissionsRequestScreen
import com.example.ui.navigation.MainAppLayer
import com.example.ui.screens.ChatViewModel
import com.example.ui.theme.Theme

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var nearbyManager: NearbyManager
    private lateinit var repository: ChatRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        database = AppDatabase.getDatabase(this)
        nearbyManager = NearbyManager(this, database.chatDao())
        repository = ChatRepository(database.chatDao(), nearbyManager)

        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var permissionsGranted by remember { mutableStateOf(false) }

                    if (!permissionsGranted) {
                        PermissionsRequestScreen(
                            onPermissionsGranted = {
                                permissionsGranted = true
                                // Auto start advertising and discovery on launch
                                nearbyManager.startAdvertising()
                                nearbyManager.startDiscovery()
                            }
                        )
                    } else {
                        val factory = ChatViewModel.Factory(repository)
                        val viewModel: ChatViewModel = viewModel(factory = factory)
                        MainAppLayer(viewModel = viewModel)
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        nearbyManager.stopAll()
    }
}
