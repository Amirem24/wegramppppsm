package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.MessageType
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
    var replyingToMessage by remember { mutableStateOf<Message?>(null) }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val contentResolver = context.contentResolver
            var name = "file"
            var size = 0L
            contentResolver.query(it, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                }
            }
            val mimeType = contentResolver.getType(it) ?: "*/*"
            viewModel.sendFile(nodeId, it, name, size, mimeType)
            coroutineScope.launch { listState.animateScrollToItem(messages.size) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(getAvatarBgColor(node.avatarRef), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = node.username.take(2).uppercase(),
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(node.username, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (node.isConnected) Color(0xFF4CAF50) else Color.Gray, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (node.isConnected) "Online (${node.transportType})" else "Offline (Last Seen: " + formatLastSeen(node.lastSeenMilli) + ")",
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
                    IconButton(onClick = { /* Call feature is simulated */ }) { Icon(Icons.Filled.Videocam, "Video") }
                    IconButton(onClick = { /* Call feature is simulated */ }) { Icon(Icons.Filled.Call, "Call") }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .imePadding()
            ) {
                // Reply Preview Panel
                replyingToMessage?.let { replyMsg ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${if (replyMsg.isFromMe) "Myself" else node.username}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (replyMsg.type == MessageType.TEXT) replyMsg.content else "📎 Attachment: ${replyMsg.fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyingToMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Main Message Input Box row
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach Document File")
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
                                viewModel.sendMessage(nodeId, inputText, replyingToMessage?.id)
                                inputText = ""
                                replyingToMessage = null
                                coroutineScope.launch {
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.size - 1)
                                    }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Message", tint = MaterialTheme.colorScheme.onPrimary)
                    }
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
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
            ) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "E2EE P2P Session Active • GoChat v2.0",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                items(messages) { message ->
                    MessageItemRow(
                        message = message, 
                        node = node, 
                        messages = messages,
                        onReplyClick = { replyingToMessage = message },
                        onAcceptFile = { viewModel.acceptFileOffer(message.id, nodeId) },
                        onRejectFile = { viewModel.rejectFileOffer(message.id, nodeId) },
                        onPauseTransfer = { viewModel.pauseFileTransfer(message.id, nodeId) },
                        onResumeTransfer = { viewModel.resumeFileTransfer(message.id, nodeId) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
fun MessageItemRow(
    message: Message,
    node: Node,
    messages: List<Message>,
    onReplyClick: () -> Unit,
    onAcceptFile: () -> Unit,
    onRejectFile: () -> Unit,
    onPauseTransfer: () -> Unit,
    onResumeTransfer: () -> Unit
) {
    val isMe = message.isFromMe
    val align = if (isMe) Alignment.End else Alignment.Start
    val shape = if (isMe) {
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    
    val bgColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        // Swipe / Tap action for Reply
        Row(
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isMe) {
                IconButton(onClick = onReplyClick, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Reply, contentDescription = "Reply", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            
            Surface(
                shape = shape,
                color = bgColor,
                modifier = Modifier.widthIn(max = 290.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Quoted message box (if this is a Reply!)
                    message.replyToId?.let { rId ->
                        val originalMsg = messages.find { it.id == rId }
                        if (originalMsg != null) {
                            Surface(
                                color = textColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(modifier = Modifier.padding(8.dp)) {
                                    // Quote vertical bar
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(30.dp)
                                            .background(if (isMe) Color.White else MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (originalMsg.isFromMe) "You" else node.username,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isMe) Color.White else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = if (originalMsg.type == MessageType.TEXT) originalMsg.content else "📎 Attachment",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = textColor.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Content Rendering (Text vs Files)
                    if (message.type != MessageType.TEXT) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = "File Type Asset", tint = textColor)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = message.fileName ?: "DocumentAttachment",
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val mbSize = message.fileSize / (1024f * 1024f)
                                    val formattedSize = if (mbSize >= 0.1f) String.format("%.2f MB", mbSize) else String.format("%.1f KB", message.fileSize / 1024f)
                                    Text(
                                        text = formattedSize,
                                        color = textColor.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Accept/Reject Dialogue offering Card
                            if (!isMe && !message.hasAccepted && message.status != MessageStatus.FILE_RECEIVED && message.status != MessageStatus.FILE_REJECTED) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Accept incoming file?", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = textColor)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Button(
                                                onClick = onAcceptFile,
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("Accept", style = MaterialTheme.typography.labelMedium)
                                            }
                                            TextButton(
                                                onClick = onRejectFile,
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Reject", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }

                            // Active progress metrics and Seek pause/resume buttons
                            if (message.status == MessageStatus.TRANSFERRING_FILE) {
                                LinearProgressIndicator(
                                    progress = { message.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${(message.progress * 100).toInt()}% • ${message.speedKbps} KB/s", color = textColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                                    Text("${message.estimatedTimeLeft}s remaining", color = textColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                                    
                                    // Pause button
                                    IconButton(onClick = onPauseTransfer, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = textColor, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else if (message.status == MessageStatus.PAUSED) {
                                // Paused status state
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Transfer Paused", color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    FilledTonalIconButton(onClick = onResumeTransfer, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else if (message.status == MessageStatus.FILE_RECEIVED || message.status == MessageStatus.DELIVERED) {
                                // Done/Received status + native Share option!
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("File received successfully.", color = textColor, style = MaterialTheme.typography.labelSmall)
                                    IconButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = message.fileMimeType ?: "*/*"
                                                    putExtra(Intent.EXTRA_TEXT, "Shared from GoChat: ${message.fileName}")
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share attachment"))
                                            } catch (e: Exception) {
                                                // fallback
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = textColor, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else if (message.status == MessageStatus.FILE_REJECTED) {
                                Text("File offer was rejected.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            } else if (message.status == MessageStatus.FAILED) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Failed: ${message.content.take(15)}...", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = onResumeTransfer) {
                                        Text("Retry", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        // Regular Text Message content
                        colorableTextMessage(message.content, textColor)
                    }
                }
            }
            if (isMe) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onReplyClick, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Reply, contentDescription = "Reply", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        
        // Status Check tick indicators and timestamps
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
                    MessageStatus.SENDING, MessageStatus.TRANSFERRING_FILE -> Icons.Outlined.AccessTime
                    MessageStatus.SENT -> Icons.Outlined.CheckCircleOutline
                    MessageStatus.DELIVERED, MessageStatus.FILE_RECEIVED -> Icons.Outlined.CheckCircle
                    MessageStatus.READ -> Icons.Outlined.CheckCircle
                    MessageStatus.FAILED -> Icons.Outlined.ErrorOutline
                    else -> null
                }
                if (icon != null) {
                    Icon(
                        icon, 
                        contentDescription = "Message Status indicator", 
                        modifier = Modifier.size(13.dp), 
                        tint = if (message.status == MessageStatus.READ) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun ColumnScope.colorableTextMessage(content: String, textColor: Color) {
    Text(
        text = content,
        color = textColor,
        style = MaterialTheme.typography.bodyLarge
    )
}
