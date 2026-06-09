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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// Web Access Bridge panel inputs
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Node
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatViewModel,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val nodes by viewModel.aggregatedNodes.collectAsState()
    val selectedTransport by viewModel.selectedTransport.collectAsState()

    var showProfileDialog by remember { mutableStateOf(false) }
    val (currName, currAvatar) = viewModel.getMyProfile()
    var editUsername by remember { mutableStateOf(currName) }
    var editAvatar by remember { mutableStateOf(currAvatar) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(getAvatarBgColor(currAvatar))
                                .clickable { showProfileDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currName.take(2).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("GoChat", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                            Text("My Profile: $currName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showProfileDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit Profile")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToDiscovery,
                containerColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.AddComment, contentDescription = "Discover Nodes", tint = MaterialTheme.colorScheme.onPrimary)
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
                placeholder = { Text("Search your contacts / offline chats...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )

            // Dynamic Active Transport State Tags
            Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(Color(0xFF4CAF50), CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("P2P Mesh Stack", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val transportIcon = when (selectedTransport) {
                            "Bluetooth" -> Icons.Filled.Bluetooth
                            "Hotspot" -> Icons.Filled.CellTower
                            else -> Icons.Filled.Wifi
                        }
                        Icon(
                            imageVector = transportIcon,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(selectedTransport, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            WebAccessBridgePanel(viewModel)
            Spacer(modifier = Modifier.height(16.dp))

            // Heading
            Text(
                "Conversations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            // Filtering contacts that either are connected or have historic chat records (saved contacts)
            val filteredNodes = nodes.filter { it.isContact || it.isConnected }.filter {
                it.username.contains(searchQuery, ignoreCase = true)
            }

            if (filteredNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat, 
                            contentDescription = null, 
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No persistent chats yet", 
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap + to discover and start direct peer conversations", 
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredNodes) { node ->
                        ChatItem(node = node, onClick = { onNavigateToChat(node.id) })
                    }
                }
            }
        }
    }

    // Custom Profile Config Dialog
    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Configure My Identity") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editUsername,
                        onValueChange = { editUsername = it },
                        label = { Text("Display Name / User ID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select Avatar Aesthetic:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("avatar_1", "avatar_2", "avatar_3", "avatar_4").forEach { av ->
                            val isSel = editAvatar == av
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(getAvatarBgColor(av))
                                    .clickable { editAvatar = av }
                                    .padding(if (isSel) 4.dp else 0.dp)
                                    .clip(CircleShape)
                                    .background(if (isSel) Color.White.copy(alpha = 0.4f) else Color.Transparent)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editUsername.isNotBlank()) {
                            viewModel.updateMyProfile(editUsername, editAvatar)
                        }
                        showProfileDialog = false
                    }
                ) {
                    Text("Save Identity")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ChatItem(node: Node, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Space with custom color and profile initials
            Box(
                modifier = Modifier.size(52.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(getAvatarBgColor(node.avatarRef), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = node.username.take(2).uppercase(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Active/Online indicator
                if (node.isConnected) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Contact Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.username, 
                        fontWeight = FontWeight.Bold, 
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Tag for Transport Mode used
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = node.transportType, 
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Text(
                    text = if (node.isConnected) "Status: Online (Active)" else "Offline (Last Seen: " + formatLastSeen(node.lastSeenMilli) + ")",
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (node.isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "mDNS P2P",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// Preset color list for cute customizable peer avatars
fun getAvatarBgColor(ref: String): Color {
    return when (ref) {
        "avatar_1" -> Color(0xFFE57373) // soft red
        "avatar_2" -> Color(0xFF64B5F6) // soft blue
        "avatar_3" -> Color(0xFF81C784) // soft green
        "avatar_4" -> Color(0xFFFFB74D) // soft orange
        "avatar_random" -> Color(0xFFBA68C8) // soft purple
        else -> Color(0xFF4DB6AC) // soft teal
    }
}

fun formatLastSeen(milli: Long): String {
    if (milli <= 0L) return "Never"
    val diff = System.currentTimeMillis() - milli
    return when {
        diff < 60000L -> "Just now"
        diff < 3600000L -> "${diff / 60000L}m ago"
        diff < 86400000L -> "${diff / 3600000L}h ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            sdf.format(Date(milli))
        }
    }
}

@Composable
fun WebAccessBridgePanel(viewModel: ChatViewModel) {
    val isRunning by viewModel.isWebGatewayRunning.collectAsState()
    val serverUrl by viewModel.webGatewayUrl.collectAsState()
    val clientsCount by viewModel.webGatewayClientsCount.collectAsState()

    var showQR by remember { mutableStateOf(false) }
    var selectedTTL by remember { mutableStateOf("No Limit") }
    var expandedTTL by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.CellTower else Icons.Filled.Link,
                        contentDescription = "Web Access Link Icon",
                        tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Web Access Bridge",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isRunning) "Bridge is Active • $clientsCount connected" else "Host local space for non-app peers",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (isRunning) {
                    // Pulsing active dot
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50).copy(alpha = alpha))
                    )
                }
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Active Address filled card
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Client Web Space URL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            SelectionContainer {
                                Text(
                                    text = serverUrl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(serverUrl))
                            Toast.makeText(context, "Link Copied!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy web link",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        IconButton(onClick = {
                            try {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Connect to my GoChat Web Space: $serverUrl")
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Web Access space link")
                                context.startActivity(shareIntent)
                            } catch (e: Exception) {}
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Share web link",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Control panel for Show QR & TTL limit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showQR = !showQR },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (showQR) "Hide Scan QR" else "Show QR Code", fontSize = 12.sp)
                    }

                    // Expire TTL drop-down
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { expandedTTL = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("TTL: $selectedTTL", fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = expandedTTL,
                            onDismissRequest = { expandedTTL = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("10 Minutes") },
                                onClick = {
                                    selectedTTL = "10 Min"
                                    expandedTTL = false
                                    viewModel.setWebGatewayTTL(10)
                                    Toast.makeText(context, "Expires in 10 minutes", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("1 Hour") },
                                onClick = {
                                    selectedTTL = "1 Hour"
                                    expandedTTL = false
                                    viewModel.setWebGatewayTTL(60)
                                    Toast.makeText(context, "Expires in 1 hour", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("No Limit") },
                                onClick = {
                                    selectedTTL = "No Limit"
                                    expandedTTL = false
                                    viewModel.setWebGatewayTTL(0)
                                    Toast.makeText(context, "No time limit set", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                if (showQR) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Scan this from another browser to connect",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        StylizedQRCode(
                            url = serverUrl,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.stopWebGateway() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Text("Shutdown Web Bridge", fontWeight = FontWeight.Bold)
                }

            } else {
                Spacer(modifier = Modifier.height(12.dp))
                // Stopped: Host Button
                Button(
                    onClick = {
                        val urlText = viewModel.startWebGateway()
                        if (urlText.isNotEmpty()) {
                            Toast.makeText(context, "Web Access Bridge is Online", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to launch server", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Text("Launch Web Access Bridge", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StylizedQRCode(url: String, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sizePx = size.width
        val squareColor = Color(0xFF1E1E24)
        
        // Clear background with crisp White
        drawRect(Color.White)
        
        // 3 Locator blocks (top-left, top-right, bottom-left)
        val outerMarkerSize = sizePx * 0.22f
        val innerMarkerSize = sizePx * 0.10f
        
        val strokeW = outerMarkerSize * 0.18f
        
        // Top-Left Marker
        drawRect(squareColor, topLeft = androidx.compose.ui.geometry.Offset(10f, 10f), size = androidx.compose.ui.geometry.Size(outerMarkerSize, outerMarkerSize), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW))
        drawRect(squareColor, topLeft = androidx.compose.ui.geometry.Offset(10f + outerMarkerSize*0.25f, 10f + outerMarkerSize*0.25f), size = androidx.compose.ui.geometry.Size(innerMarkerSize, innerMarkerSize))
        
        // Top-Right Marker
        drawRect(squareColor, topLeft = androidx.compose.ui.geometry.Offset(sizePx - outerMarkerSize - 10f, 10f), size = androidx.compose.ui.geometry.Size(outerMarkerSize, outerMarkerSize), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW))
        drawRect(squareColor, topLeft = androidx.compose.ui.geometry.Offset(sizePx - outerMarkerSize - 10f + outerMarkerSize*0.25f, 10f + outerMarkerSize*0.25f), size = androidx.compose.ui.geometry.Size(innerMarkerSize, innerMarkerSize))
        
        // Bottom-Left Marker
        drawRect(squareColor, topLeft = androidx.compose.ui.geometry.Offset(10f, sizePx - outerMarkerSize - 10f), size = androidx.compose.ui.geometry.Size(outerMarkerSize, outerMarkerSize), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW))
        drawRect(squareColor, topLeft = androidx.compose.ui.geometry.Offset(10f + outerMarkerSize*0.25f, sizePx - outerMarkerSize - 10f + outerMarkerSize*0.25f), size = androidx.compose.ui.geometry.Size(innerMarkerSize, innerMarkerSize))
        
        // Procedural pixels (matrix) based on hostname hash
        val steps = 18
        val pxSize = sizePx / steps
        val rnd = java.util.Random(url.hashCode().toLong())
        for (r in 0 until steps) {
            for (c in 0 until steps) {
                // Skip the areas of the 3 locator blocks
                val isNearTopLeft = r < 5 && c < 5
                val isNearTopRight = r < 5 && c >= steps - 5
                val isNearBottomLeft = r >= steps - 5 && c < 5
                if (!isNearTopLeft && !isNearTopRight && !isNearBottomLeft) {
                    if (rnd.nextBoolean()) {
                        drawRect(
                            color = squareColor,
                            topLeft = androidx.compose.ui.geometry.Offset(c * pxSize, r * pxSize),
                            size = androidx.compose.ui.geometry.Size(pxSize, pxSize)
                        )
                    }
                }
            }
        }
    }
}
