package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Node

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatViewModel,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val nodes by viewModel.aggregatedNodes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MeshChat", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                actions = {
                    IconButton(onClick = { /* Settings */ }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToDiscovery,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.AddComment, contentDescription = "Discover", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                placeholder = { Text("Search local nodes...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )

            // Status Tags
            Row(modifier = Modifier.padding(bottom = 16.dp)) {
                Surface(
                    color = Color(0xFF4CAF50).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("P2P Mesh Active", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Bluetooth",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // List of Chats (Nodes that we have connected to or saved)
            val filteredNodes = nodes.filter { it.isConnected || it.lastSeenMilli > 0 }.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(filteredNodes) { node ->
                    ChatItem(node = node, onClick = { onNavigateToChat(node.id) })
                }
            }
        }
    }
}

@Composable
fun ChatItem(node: Node, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier.size(56.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    node.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            // Online Indicator
            if (node.isConnected) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(text = node.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(text = if (node.isConnected) "Connected now" else "Offline", maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Text(text = "10:42 AM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
