package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Node

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedTransport by viewModel.selectedTransport.collectAsState()
    val discoveredNodes by viewModel.discoveredNodes.collectAsState()
    
    val options = listOf("Bluetooth", "Hotspot", "Local Wi-Fi")
    
    // Auto-discover start when enters Discovery screen
    LaunchedEffect(Unit) {
        viewModel.startNearby()
    }
    
    // Map selected transport filter to UI items
    val filteredNodes = discoveredNodes.filter { node ->
        // Direct transport classification matching the current option
        val matchesSelected = when (selectedTransport) {
            "Bluetooth" -> node.transportType.lowercase().contains("bluetooth") || node.transportType.lowercase().contains("ble") || node.transportType == "Auto"
            "Hotspot" -> node.transportType.lowercase().contains("hotspot")
            "Local Wi-Fi" -> node.transportType.lowercase().contains("wi-fi") || node.transportType.lowercase().contains("wifi")
            else -> true
        }
        matchesSelected && node.username.contains(searchQuery, ignoreCase = true)
    }.sortedByDescending { it.signalStrength }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("P2P Explorer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            
            // Selector segments for standard M3 transport types
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    val isSelected = selectedTransport == option
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setSelectedTransport(option) },
                        label = { Text(option) },
                        leadingIcon = {
                            val icon = when (option) {
                                "Bluetooth" -> Icons.Filled.Bluetooth
                                "Hotspot" -> Icons.Filled.CellTower
                                else -> Icons.Filled.Wifi
                            }
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            // Pulse Scanning feedback indicator bar
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pulseTransition = rememberInfiniteTransition(label = "StatusRadius")
                    val pulseRadius by pulseTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1400, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50).copy(alpha = pulseRadius))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Broadcasting & Scanning on $selectedTransport Mode...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            
            // Interactive Radar sweep loop
            RadarVisualization()
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                placeholder = { Text("Filter discovered nodes nearby...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp)
            )
            
            Text(
                "Nearby Nodes (${filteredNodes.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Discovered nodes listing
            if (filteredNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Waiting for other Mesh devices...",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredNodes) { node ->
                        NodeCardItem(
                            node = node,
                            onClick = { 
                                viewModel.connectToNode(node.id)
                                onNavigateToChat(node.id) 
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RadarVisualization() {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val scale = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarScale"
    )
    val opacity = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarOpacity"
    )
    
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val baseRadius = 25.dp.toPx()
            
            // Concentric sweep rings
            drawCircle(color = primaryColor.copy(alpha = 0.08f), radius = baseRadius * 1.5f, style = Stroke(width = 1.5f))
            drawCircle(color = primaryColor.copy(alpha = 0.04f), radius = baseRadius * 2.8f, style = Stroke(width = 1.5f))
            
            // Animated radar wave expansion
            drawCircle(
                color = primaryColor.copy(alpha = opacity.value),
                radius = baseRadius * scale.value,
                style = Stroke(width = 2.5f)
            )
            
            // Core beacon hub
            drawCircle(
                color = primaryColor,
                radius = baseRadius * 0.7f
            )
        }
        Text(
            text = "My Beacon", 
            modifier = Modifier.offset(y = 35.dp), 
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = primaryColor
        )
    }
}

@Composable
fun NodeCardItem(node: Node, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("node_item_${node.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circle Avatar for peer initials
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(getAvatarBgColor(node.avatarRef)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = node.username.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Identity credentials (User ID, IP and Model info)
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
                    
                    // Connected/Paired pill
                    Surface(
                        color = if (node.isConnected) Color(0xFFE8F5E9) else Color(0xFFECEFF1),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (node.pairStatus == "Paired") "Paired" else "Peer",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (node.isConnected) Color(0xFF2E7D32) else Color(0xFF546E7A),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${node.id.take(8)} • ${node.deviceModel}", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "IP: ${node.ipAddress}", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Connect invitation button + Signal visual bars
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                // Signal Quality indicators (render WiFi signals blocks)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (i in 1..5) {
                        val isLit = i <= node.signalStrength
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height((4 + (i * 3)).dp)
                                .background(
                                    if (isLit) Color(0xFF4CAF50) else Color.LightGray.copy(alpha = 0.5f),
                                    RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                FilledIconButton(
                    onClick = onClick,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add, 
                        contentDescription = "Add Contact",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
