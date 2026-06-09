package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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
    val nodes by viewModel.aggregatedNodes.collectAsState()
    val selectedTransport by viewModel.selectedTransport.collectAsState()
    
    // Filtering
    var searchQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val filteredNodes = nodes.filter { !it.isConnected && it.name.contains(searchQuery, ignoreCase = true) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discovery", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startNearby() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Transport Selection
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                val options = listOf("Bluetooth", "Hotspot", "Local Wi-Fi")
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedTransport == label,
                        onClick = { viewModel.setSelectedTransport(label) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            
            // Scanning Status Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scanning via $selectedTransport...", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelLarge)
            }
            
            // Radar Visualization
            RadarVisualization()
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                placeholder = { Text("Filter users...") },
                shape = RoundedCornerShape(12.dp)
            )
            
            Text(
                "Nearby Nodes (${filteredNodes.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Node List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredNodes) { node ->
                    NodeCardItem(
                        node = node,
                        onClick = { viewModel.connectToNode(node.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun RadarVisualization() {
    val infiniteTransition = rememberInfiniteTransition(label = "Radar")
    val scale = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarScale"
    )
    val opacity = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarOpacity"
    )
    
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val baseRadius = 40.dp.toPx()
            
            // Draw static circles
            drawCircle(color = primaryColor.copy(alpha = 0.1f), radius = baseRadius * 1.5f, style = Stroke(width = 1f))
            drawCircle(color = primaryColor.copy(alpha = 0.05f), radius = baseRadius * 2.5f, style = Stroke(width = 1f))
            
            // Draw animating circle
            drawCircle(
                color = primaryColor.copy(alpha = opacity.value),
                radius = baseRadius * scale.value,
                style = Stroke(width = 2f)
            )
            
            // Draw center dot
            drawCircle(
                color = primaryColor,
                radius = baseRadius * 0.8f
            )
        }
        Text("My Device", modifier = Modifier.offset(y = 50.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun NodeCardItem(node: Node, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Placeholder
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    node.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(text = node.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(text = node.deviceModel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            // Status Tag & Add button
            Column(horizontalAlignment = Alignment.End) {
                val tagColor = if (node.signalStrength >= 3) Color(0xFF4CAF50) else if (node.signalStrength == 2) Color(0xFF81C784) else Color(0xFFE0E0E0)
                Surface(
                    color = tagColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (node.signalStrength >= 3) "Strong Signal" else if (node.signalStrength == 2) "Good Signal" else "Weak Signal",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Connect",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
    }
}
