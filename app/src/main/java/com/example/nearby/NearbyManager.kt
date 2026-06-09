package com.example.nearby

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.local.ChatDao
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.MessageType
import com.example.data.model.Node
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NearbyManager(private val context: Context, private val chatDao: ChatDao) {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER // Mesh-like topology
    private val SERVICE_ID = "com.aistudio.gochat"
    
    // User profile states (supports custom configuration)
    var myUsername: String = android.os.Build.MODEL
    var myAvatar: String = "avatar_1"
    val myUserId: String = "gochat-p2p-" + UUID.randomUUID().toString().take(6)
    private var myEndpointName: String = "User_$myUsername"

    private val _discoveredEndpoints = MutableStateFlow<List<Node>>(emptyList())
    val discoveredEndpoints = _discoveredEndpoints.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints = _connectedEndpoints.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Chunk-Based FTP collections
    private val pendingAcks = ConcurrentHashMap<String, Boolean>()
    private val activeTransfers = ConcurrentHashMap<String, Job>()

    // Selected Transport state (Bluetooth, Wi-Fi, Hotspot) to enrich metadata
    var currentTransportMode: String = "Auto"

    init {
        // Start Presence Service / Keep-Alive timeout prune loop
        scope.launch {
            while (true) {
                delay(3000)
                val now = System.currentTimeMillis()
                // If we haven't seen a discovered endpoint in 12 seconds, remove it (Offline State detection)
                val oldList = _discoveredEndpoints.value
                val filteredList = oldList.filter { node ->
                    (now - node.lastSeenMilli) < 12000
                }
                if (filteredList.size != oldList.size) {
                    _discoveredEndpoints.value = filteredList
                    Log.d("PresenceService", "Pruned offline nodes. Size: ${filteredList.size}")
                }
                
                // Keep-alive: broadcast our presence to all active connections
                _connectedEndpoints.value.forEach { endpointId ->
                    sendPresence(endpointId)
                }
            }
        }
    }

    // Callbacks for connections
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("NearbyManager", "Connection initiated with endpoint $endpointId")
            // Automatically accept connection payload
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            
            val isBluetooth = currentTransportMode.lowercase().contains("bluetooth")
            val defaultIp = if (isBluetooth) "Bluetooth LE" else "192.168.43.34"

            val initialNode = Node(
                id = endpointId,
                name = info.endpointName,
                deviceModel = android.os.Build.MODEL,
                lastSeenMilli = System.currentTimeMillis(),
                isConnected = true,
                signalStrength = 4,
                transportType = currentTransportMode,
                ipAddress = defaultIp,
                username = info.endpointName,
                avatarRef = "avatar_random",
                connectionState = "Connected",
                pairStatus = "Paired",
                isContact = true // Chat Session requested - save as persistent chat record
            )
            scope.launch {
                chatDao.insertNode(initialNode)
                _connectedEndpoints.value = _connectedEndpoints.value + endpointId
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("NearbyManager", "Connected success to $endpointId")
                    scope.launch {
                        _connectedEndpoints.value = _connectedEndpoints.value + endpointId
                        // Send presence packet instantly to fulfill bidirectional profile exchange
                        sendPresence(endpointId)
                        // Trigger a session established sync event
                        sendChatHandshake(endpointId)
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("NearbyManager", "Connection rejected by $endpointId")
                    scope.launch {
                        _connectedEndpoints.value = _connectedEndpoints.value - endpointId
                        val node = chatDao.getNodeById(endpointId)
                        node?.let { chatDao.updateNode(it.copy(isConnected = false, connectionState = "Offline")) }
                    }
                }
                else -> {
                    Log.e("NearbyManager", "Connection failed with code: ${result.status.statusCode}")
                    scope.launch {
                        _connectedEndpoints.value = _connectedEndpoints.value - endpointId
                        val node = chatDao.getNodeById(endpointId)
                        node?.let { chatDao.updateNode(it.copy(isConnected = false, connectionState = "Offline")) }
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("NearbyManager", "Disconnected from $endpointId")
            scope.launch {
                _connectedEndpoints.value = _connectedEndpoints.value - endpointId
                val node = chatDao.getNodeById(endpointId)
                node?.let { chatDao.updateNode(it.copy(isConnected = false, connectionState = "Offline")) }
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d("NearbyManager", "Endpoint found in scan: $endpointId (${info.endpointName})")
            
            // Enrich simulated signal and transport details based on user selected filter
            val signalStrength = (3..5).random()
            val simulatedIp = when (currentTransportMode) {
                "Hotspot" -> "192.168.43." + (10..254).random()
                "Local Wi-Fi" -> "192.168.1." + (10..254).random()
                else -> "Bluetooth Core"
            }
            
            val discoveredNode = Node(
                id = endpointId,
                name = info.endpointName,
                deviceModel = android.os.Build.MODEL,
                lastSeenMilli = System.currentTimeMillis(),
                isConnected = false,
                signalStrength = signalStrength,
                transportType = currentTransportMode,
                ipAddress = simulatedIp,
                username = info.endpointName,
                avatarRef = "avatar_" + (1..6).random(),
                connectionState = "Online",
                pairStatus = if (isClassicPaired(info.endpointName)) "Paired" else "Unpaired",
                isContact = false
            )
            
            // Add or update list of discovered nodes
            val list = _discoveredEndpoints.value.toMutableList()
            val existingIndex = list.indexOfFirst { it.id == endpointId }
            if (existingIndex >= 0) {
                list[existingIndex] = discoveredNode
            } else {
                list.add(discoveredNode)
            }
            _discoveredEndpoints.value = list
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("NearbyManager", "Endpoint lost: $endpointId")
            _discoveredEndpoints.value = _discoveredEndpoints.value.filter { it.id != endpointId }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val jsonString = String(bytes, Charsets.UTF_8)
                try {
                    val json = JSONObject(jsonString)
                    val action = json.optString("action")
                    Log.d("NearbyProtocol", "Action received: $action from $endpointId")
                    
                    when (action) {
                        "PRESENCE" -> {
                            val userId = json.optString("userId", endpointId)
                            val username = json.optString("username", "Peer Node")
                            val avatarRef = json.optString("avatarRef", "avatar_1")
                            val deviceModel = json.optString("deviceModel", "P2P Device")
                            val supportedTransports = json.optString("supportedTransports", "Bluetooth/Wi-Fi")
                            val protocolVersion = json.optString("protocolVersion", "gochat-v2.0")
                            val lastSeen = json.optLong("lastSeen", System.currentTimeMillis())
                            
                            // Dynamically update peer structure inside Local SQLite DB
                            scope.launch {
                                val existingNode = chatDao.getNodeById(endpointId)
                                val updatedNode = existingNode?.copy(
                                    name = username,
                                    username = username,
                                    deviceModel = deviceModel,
                                    avatarRef = avatarRef,
                                    lastSeenMilli = lastSeen,
                                    supportedTransports = supportedTransports,
                                    protocolVersion = protocolVersion,
                                    connectionState = "Connected",
                                    isConnected = true
                                ) ?: Node(
                                    id = endpointId,
                                    name = username,
                                    deviceModel = deviceModel,
                                    lastSeenMilli = lastSeen,
                                    isConnected = true,
                                    signalStrength = 4,
                                    transportType = currentTransportMode,
                                    ipAddress = if (currentTransportMode.lowercase().contains("bluetooth")) "Bluetooth LE" else "192.168.1.?",
                                    userId = userId,
                                    username = username,
                                    avatarRef = avatarRef,
                                    supportedTransports = supportedTransports,
                                    protocolVersion = protocolVersion,
                                    connectionState = "Connected",
                                    pairStatus = "Paired",
                                    isContact = true
                                )
                                chatDao.insertNode(updatedNode)
                            }
                        }
                        "CHAT_HANDSHAKE" -> {
                            val sessionId = json.optString("sessionId", UUID.randomUUID().toString())
                            Log.d("NearbyManager", "Chat session received with ID: $sessionId")
                        }
                        "MESSAGE" -> {
                            val messageId = json.getString("id")
                            val content = json.getString("content")
                            val replyToId = json.optString("replyToId", null).takeIf { it.isNotEmpty() }
                            
                            val msg = Message(
                                id = messageId,
                                senderId = endpointId,
                                receiverId = "me",
                                content = content,
                                timestamp = System.currentTimeMillis(),
                                type = MessageType.TEXT,
                                status = MessageStatus.DELIVERED,
                                isFromMe = false,
                                replyToId = replyToId
                            )
                            scope.launch {
                                chatDao.insertMessage(msg)
                                sendReceipt(endpointId, messageId, "DELIVERED")
                            }
                        }
                        "FILE_META" -> {
                            // Peer wants to send a file - we notify UI via DB offering State
                            val messageId = json.getString("id")
                            val fileName = json.getString("fileName")
                            val fileSize = json.getLong("fileSize")
                            val fileMimeType = json.getString("fileMimeType")
                            val fileHash = json.optString("fileHash", "")
                            
                            val itemType = getMessageTypeFromMime(fileMimeType)
                            val msg = Message(
                                id = messageId,
                                senderId = endpointId,
                                receiverId = "me",
                                content = "File Offered: $fileName",
                                timestamp = System.currentTimeMillis(),
                                type = itemType,
                                status = MessageStatus.TRANSFERRING_FILE,
                                isFromMe = false,
                                fileName = fileName,
                                fileSize = fileSize,
                                fileMimeType = fileMimeType,
                                progress = 0f,
                                speedKbps = 0,
                                fileHash = fileHash,
                                hasAccepted = false // Receiver needs to hit Accept in UI first
                            )
                            scope.launch {
                                chatDao.insertMessage(msg)
                            }
                        }
                        "FILE_ACCEPT" -> {
                            val messageId = json.getString("messageId")
                            Log.d("ChunkSend", "Peer accepted file Meta offer for $messageId. Starting stream...")
                            scope.launch {
                                val msg = chatDao.getMessageById(messageId)
                                if (msg != null && msg.fileUri != null) {
                                    startChunkedBufferSend(endpointId, msg, android.net.Uri.parse(msg.fileUri))
                                }
                            }
                        }
                        "FILE_REJECT" -> {
                            val messageId = json.getString("messageId")
                            Log.d("ChunkSend", "Peer rejected file offer for $messageId")
                            scope.launch {
                                val msg = chatDao.getMessageById(messageId)
                                if (msg != null) {
                                    chatDao.updateMessage(msg.copy(
                                        status = MessageStatus.FILE_REJECTED,
                                        content = "File Rejected by recipient"
                                    ))
                                }
                            }
                        }
                        "FILE_PAUSE" -> {
                            val messageId = json.getString("messageId")
                            Log.d("ChunkFTP", "Pause requested by peer for $messageId")
                            scope.launch {
                                val msg = chatDao.getMessageById(messageId)
                                if (msg != null) {
                                    chatDao.updateMessage(msg.copy(status = MessageStatus.PAUSED, isPaused = true))
                                    activeTransfers[messageId]?.cancel()
                                }
                            }
                        }
                        "FILE_RESUME" -> {
                            val messageId = json.getString("messageId")
                            Log.d("ChunkFTP", "Resume requested by peer for $messageId")
                            scope.launch {
                                val msg = chatDao.getMessageById(messageId)
                                if (msg != null) {
                                    chatDao.updateMessage(msg.copy(status = MessageStatus.TRANSFERRING_FILE, isPaused = false))
                                    // If we are sender, start the stream again
                                    if (msg.isFromMe && msg.fileUri != null) {
                                        startChunkedBufferSend(endpointId, msg, android.net.Uri.parse(msg.fileUri))
                                    }
                                }
                            }
                        }
                        "FILE_CHUNK" -> {
                            val messageId = json.getString("messageId")
                            val chunkIndex = json.getInt("chunkIndex")
                            val totalChunks = json.getInt("totalChunks")
                            val checksum = json.getString("checksum")
                            val dataBase64 = json.getString("data")
                            val bytesRead = json.getInt("bytesRead")
                            
                            val chunkData = Base64.decode(dataBase64, Base64.NO_WRAP)
                            val verifiedCheck = calculateSimpleChecksum(chunkData, bytesRead)
                            
                            if (verifiedCheck == checksum) {
                                // ACK correct chunk index back to sender
                                sendAck(endpointId, messageId, chunkIndex, "OK")
                                
                                // Real-Time DB progress updates
                                scope.launch {
                                    val msg = chatDao.getMessageById(messageId)
                                    if (msg != null) {
                                        val totalSize = msg.fileSize
                                        val progress = (chunkIndex + 1).toFloat() / totalChunks.toFloat()
                                        val speed = (180..240).random().toLong() // Simulated KB/s
                                        val transferredBytes = (chunkIndex + 1).toLong() * (16 * 1024)
                                        val timeLeft = if (speed > 0) ((totalSize - transferredBytes) / (speed * 1024L)).coerceAtLeast(0L) else 0L
                                        
                                        val isDone = (chunkIndex + 1) >= totalChunks
                                        chatDao.updateMessage(msg.copy(
                                            currentChunkIndex = chunkIndex + 1,
                                            totalChunks = totalChunks,
                                            progress = progress,
                                            speedKbps = speed,
                                            estimatedTimeLeft = timeLeft,
                                            status = if (isDone) MessageStatus.FILE_RECEIVED else MessageStatus.TRANSFERRING_FILE,
                                            content = if (isDone) "File Received" else "Transferring File..."
                                        ))
                                    }
                                }
                            } else {
                                Log.w("ChunkFTP", "Checksum verification failed on received chunk $chunkIndex")
                                sendAck(endpointId, messageId, chunkIndex, "CHECKSUM_ERROR")
                            }
                        }
                        "FILE_ACK" -> {
                            val messageId = json.getString("messageId")
                            val chunkIndex = json.getInt("chunkIndex")
                            val status = json.getString("status")
                            if (status == "OK") {
                                pendingAcks["${messageId}_$chunkIndex"] = true
                            }
                        }
                        "RECEIPT" -> {
                            val messageId = json.getString("messageId")
                            val statusStr = json.getString("status")
                            val status = try { MessageStatus.valueOf(statusStr) } catch (e: Exception) { MessageStatus.DELIVERED }
                            scope.launch {
                                val msg = chatDao.getMessageById(messageId)
                                if (msg != null && msg.status < status) {
                                    chatDao.updateMessage(msg.copy(status = status))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NearbyManager", "JSON payload handle exception", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Legacy / Standard connection callback streams are handled here if native payload transfers are triggered
        }
    }

    // Handshakes & Presences
    fun sendPresence(endpointId: String) {
        try {
            val json = JSONObject().apply {
                put("action", "PRESENCE")
                put("userId", myUserId)
                put("username", myUsername)
                put("avatarRef", myAvatar)
                put("deviceModel", android.os.Build.MODEL)
                put("supportedTransports", "Bluetooth, Wi-Fi, Hotspot")
                put("protocolVersion", "gochat-v2.0")
                put("lastSeen", System.currentTimeMillis())
            }
            val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e("NearbyManager", "Failed to transfer presence", e)
        }
    }

    private fun sendChatHandshake(endpointId: String) {
        try {
            val json = JSONObject().apply {
                put("action", "CHAT_HANDSHAKE")
                put("sessionId", UUID.randomUUID().toString())
            }
            val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e("NearbyManager", "Failed to send chat handshake", e)
        }
    }

    // Commands
    fun startAdvertising(transportMode: String = "Auto") {
        currentTransportMode = transportMode
        myEndpointName = "User_$myUsername"
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myEndpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d("NearbyManager", "Bidirectional Advertising started on: $transportMode")
        }.addOnFailureListener { e ->
            Log.e("NearbyManager", "Advertising failed", e)
        }
    }

    fun startDiscovery(transportMode: String = "Auto") {
        currentTransportMode = transportMode
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d("NearbyManager", "Bidirectional Discovery started on: $transportMode")
        }.addOnFailureListener { e ->
            Log.e("NearbyManager", "Discovery failed", e)
        }
    }
    
    // Custom chunk-based initiator
    fun startChunkedBufferSend(endpointId: String, message: Message, uri: android.net.Uri) {
        // Cancel active transfer thread if already in progress for this message ID
        activeTransfers[message.id]?.cancel()
        
        val job = scope.launch(Dispatchers.IO) {
            try {
                val inputStream: InputStream = context.contentResolver.openInputStream(uri) 
                    ?: throw Exception("Could not open file stream")
                
                val totalSize = message.fileSize
                val chunkSize = 16 * 1024 // 16KB standard size
                val totalChunks = if (totalSize <= 0L) 1 else (((totalSize + chunkSize - 1) / chunkSize).toInt())
                
                val lastAckIndex = message.currentChunkIndex
                val skipBytes = lastAckIndex.toLong() * chunkSize
                inputStream.skip(skipBytes)
                
                var chunkIndex = lastAckIndex
                val buffer = ByteArray(chunkSize)
                
                chatDao.updateMessage(message.copy(
                    totalChunks = totalChunks,
                    status = MessageStatus.TRANSFERRING_FILE,
                    isPaused = false
                ))

                while (chunkIndex < totalChunks) {
                    // Check if transfer has been paused manually or cancelled
                    val realMsg = chatDao.getMessageById(message.id)
                    if (realMsg == null || realMsg.isPaused || realMsg.status == MessageStatus.PAUSED || realMsg.status == MessageStatus.FAILED) {
                        Log.d("ChunkedFTP", "Send loops paused for $chunkIndex")
                        break
                    }
                    
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    
                    val checksum = calculateSimpleChecksum(buffer, bytesRead)
                    val encodedData = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP)
                    
                    val chunkJson = JSONObject().apply {
                        put("action", "FILE_CHUNK")
                        put("messageId", message.id)
                        put("chunkIndex", chunkIndex)
                        put("totalChunks", totalChunks)
                        put("checksum", checksum)
                        put("data", encodedData)
                        put("bytesRead", bytesRead)
                    }
                    
                    val payload = Payload.fromBytes(chunkJson.toString().toByteArray(Charsets.UTF_8))
                    
                    var ackOk = false
                    var retryLimit = 3
                    while (!ackOk && retryLimit > 0) {
                        connectionsClient.sendPayload(endpointId, payload)
                        
                        // Wait for chunk ACK index
                        val ackKey = "${message.id}_$chunkIndex"
                        var waitLimit = 40 // 40 * 50ms = 2 seconds
                        while (waitLimit > 0 && !pendingAcks.containsKey(ackKey)) {
                            delay(50)
                            waitLimit--
                        }
                        if (pendingAcks.containsKey(ackKey)) {
                            ackOk = true
                            pendingAcks.remove(ackKey)
                        } else {
                            retryLimit--
                            Log.w("ChunkedFTP", "Timeout waiting ACK on chunk $chunkIndex. Retrying ($retryLimit remaining)")
                            delay(100)
                        }
                    }

                    if (!ackOk) {
                        // Transfer failed due to packet loss/connection drops
                        chatDao.updateMessage(message.copy(
                            status = MessageStatus.FAILED,
                            content = "Failed: Transfer timeout"
                        ))
                        break
                    }
                    
                    // Advance chunk metrics
                    chunkIndex++
                    val progressFloat = chunkIndex.toFloat() / totalChunks.toFloat()
                    val speed = (180..240).random().toLong() // Simulated KB/s
                    val transferredBytes = chunkIndex.toLong() * chunkSize
                    val left = if (speed > 0) ((totalSize - transferredBytes) / (speed * 1024L)).coerceAtLeast(0L) else 0L
                    
                    chatDao.updateMessage(message.copy(
                        currentChunkIndex = chunkIndex,
                        progress = progressFloat,
                        speedKbps = speed,
                        estimatedTimeLeft = left
                    ))
                    
                    delay(15) // small pacing gap to avoid buffer overflows
                }
                
                inputStream.close()
                if (chunkIndex >= totalChunks) {
                    chatDao.updateMessage(message.copy(
                        progress = 1.0f,
                        status = MessageStatus.FILE_RECEIVED,
                        content = "File Received"
                    ))
                }
            } catch (e: Exception) {
                Log.e("ChunkedFTP", "Exception during send", e)
                chatDao.updateMessage(message.copy(
                    status = MessageStatus.FAILED,
                    content = e.message ?: "File Transfer Error"
                ))
            }
        }
        activeTransfers[message.id] = job
    }

    // Send payload offering metadata prior to actual packet chunking
    fun sendFileMessage(endpointId: String, message: Message, uri: android.net.Uri) {
        try {
            val json = JSONObject().apply {
                put("action", "FILE_META")
                put("id", message.id)
                put("fileName", message.fileName)
                put("fileSize", message.fileSize)
                put("fileMimeType", message.fileMimeType)
                put("fileHash", message.fileHash ?: UUID.randomUUID().toString().take(8))
            }
            
            val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload).addOnSuccessListener {
                Log.d("ChunkFTP", "File offer metadata sent to $endpointId")
            }
        } catch (e: Exception) {
            Log.e("NearbyManager", "Failed to offer file message", e)
        }
    }
    
    // Acceptance flow trigger
    fun acceptFileOffer(messageId: String, endpointId: String) {
        scope.launch {
            val msg = chatDao.getMessageById(messageId)
            if (msg != null) {
                chatDao.updateMessage(msg.copy(hasAccepted = true, status = MessageStatus.TRANSFERRING_FILE))
                
                val json = JSONObject().apply {
                    put("action", "FILE_ACCEPT")
                    put("messageId", messageId)
                }
                val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
    }

    fun rejectFileOffer(messageId: String, endpointId: String) {
        scope.launch {
            val msg = chatDao.getMessageById(messageId)
            if (msg != null) {
                chatDao.updateMessage(msg.copy(hasAccepted = false, status = MessageStatus.FILE_REJECTED, content = "Rejected File Option"))
                
                val json = JSONObject().apply {
                    put("action", "FILE_REJECT")
                    put("messageId", messageId)
                }
                val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
    }

    fun pauseFileTransfer(messageId: String, endpointId: String) {
        scope.launch {
            val msg = chatDao.getMessageById(messageId)
            if (msg != null) {
                chatDao.updateMessage(msg.copy(isPaused = true, status = MessageStatus.PAUSED))
                activeTransfers[messageId]?.cancel()
                
                val json = JSONObject().apply {
                    put("action", "FILE_PAUSE")
                    put("messageId", messageId)
                }
                val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
    }

    fun resumeFileTransfer(messageId: String, endpointId: String) {
        scope.launch {
            val msg = chatDao.getMessageById(messageId)
            if (msg != null) {
                chatDao.updateMessage(msg.copy(isPaused = false, status = MessageStatus.TRANSFERRING_FILE))
                
                val json = JSONObject().apply {
                    put("action", "FILE_RESUME")
                    put("messageId", messageId)
                }
                val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
                connectionsClient.sendPayload(endpointId, payload).addOnSuccessListener {
                    if (msg.isFromMe && msg.fileUri != null) {
                        startChunkedBufferSend(endpointId, msg, android.net.Uri.parse(msg.fileUri))
                    }
                }
            }
        }
    }

    fun connectToEndpoint(endpointId: String) {
        connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d("NearbyManager", "Requested connection to $endpointId successfully")
            }.addOnFailureListener { e ->
                Log.e("NearbyManager", "Request connection failed", e)
            }
    }

    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        scope.launch {
            _connectedEndpoints.value = _connectedEndpoints.value - endpointId
            val node = chatDao.getNodeById(endpointId)
            node?.let { chatDao.updateNode(it.copy(isConnected = false, connectionState = "Offline")) }
        }
    }

    fun sendTextMessage(endpointId: String, message: Message) {
        val json = JSONObject().apply {
            put("action", "MESSAGE")
            put("id", message.id)
            put("content", message.content)
            put("replyToId", message.replyToId ?: "")
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        connectionsClient.sendPayload(endpointId, payload).addOnSuccessListener {
            scope.launch {
                chatDao.updateMessage(message.copy(status = MessageStatus.SENT))
            }
        }
    }
    
    private fun sendReceipt(endpointId: String, messageId: String, status: String) {
        try {
            val json = JSONObject().apply {
                put("action", "RECEIPT")
                put("messageId", messageId)
                put("status", status)
            }
            val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e("NearbyManager", "Failed to send receipt", e)
        }
    }

    private fun sendAck(endpointId: String, messageId: String, chunkIndex: Int, status: String) {
        try {
            val json = JSONObject().apply {
                put("action", "FILE_ACK")
                put("messageId", messageId)
                put("chunkIndex", chunkIndex)
                put("status", status)
            }
            val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e("NearbyManager", "Failed to acknowledge chunk", e)
        }
    }
    
    private fun calculateSimpleChecksum(bytes: ByteArray, length: Int): String {
        var checksum = 0L
        for (i in 0 until length) {
            checksum = (checksum + bytes[i].toLong() * (i + 1)) % 1000000007L
        }
        return checksum.toString(16)
    }

    private fun getMessageTypeFromMime(mimeType: String): MessageType {
        return when {
            mimeType.startsWith("image/") -> MessageType.IMAGE
            mimeType.startsWith("video/") -> MessageType.VIDEO
            mimeType.startsWith("audio/") -> MessageType.AUDIO
            mimeType.startsWith("text/") || mimeType.contains("pdf") || mimeType.contains("word") -> MessageType.DOCUMENT
            else -> MessageType.FILE
        }
    }

    private fun isClassicPaired(deviceName: String): Boolean {
        // Mock classic paired status check based on simple hashing or name checks
        return deviceName.hashCode() % 3 == 0
    }
    
    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _connectedEndpoints.value = emptySet()
    }
}
