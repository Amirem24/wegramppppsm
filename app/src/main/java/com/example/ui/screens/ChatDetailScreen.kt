package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Node
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    nodeId: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit
) {
    val messages by viewModel.getMessagesForNode(nodeId).collectAsState()
    val nodes by viewModel.aggregatedNodes.collectAsState()
    val node = nodes.find { it.id == nodeId } ?: Node(nodeId, "Unknown", "Unknown", 0L, false)

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(node.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(node.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (node.isConnected) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (node.isConnected) "Connected via LAN" else "Disconnected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* Call */ }) { Icon(Icons.Filled.Videocam, "Video") }
                    IconButton(onClick = { /* Call */ }) { Icon(Icons.Filled.Call, "Call") }
                    IconButton(onClick = { /* More */ }) { Icon(Icons.Filled.MoreVert, "More") }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.consumeWindowInsets(WindowInsets.navigationBars),
                containerColor = MaterialTheme.colorScheme.surface,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = { /* Attach */ }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(nodeId, inputText)
                            inputText = ""
                            coroutineScope.launch {
                                listState.animateScrollToItem(messages.size)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
            ) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                "P2P Mesh Established",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                items(messages) { message ->
                    MessageBubble(message = message)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val isMe = message.isFromMe
    val align = if (isMe) Alignment.End else Alignment.Start
    val shape = if (isMe) {
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    val bgColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(
            shape = shape,
            color = bgColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp, end = if (isMe) 4.dp else 0.dp, start = if (!isMe) 4.dp else 0.dp)
        ) {
            Text(
                text = timeFormat.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            if (isMe) {
                Spacer(modifier = Modifier.width(4.dp))
                val icon = when (message.status) {
                    MessageStatus.SENDING -> Icons.Outlined.AccessTime
                    MessageStatus.SENT -> Icons.Outlined.CheckCircleOutline
                    MessageStatus.DELIVERED -> Icons.Outlined.CheckCircle
                    MessageStatus.READ -> Icons.Outlined.CheckCircle // Can color differently for read
                    MessageStatus.FAILED -> null
                }
                if (icon != null) {
                    Icon(
                        icon, 
                        contentDescription = "Status", 
                        modifier = Modifier.size(14.dp), 
                        tint = if (message.status == MessageStatus.READ) Color(0xFF2196F3) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
