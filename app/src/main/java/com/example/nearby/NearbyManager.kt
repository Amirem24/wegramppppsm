package com.example.nearby

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class NearbyManager(private val context: Context, private val chatDao: ChatDao) {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER // Mesh-like topology
    private val SERVICE_ID = "com.aistudio.gochat"
    
    private var myEndpointName: String = android.os.Build.MODEL

    private val _discoveredEndpoints = MutableStateFlow<List<Node>>(emptyList())
    val discoveredEndpoints = _discoveredEndpoints.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints = _connectedEndpoints.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO)

    // Callbacks for connections
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Automatically accept connections for simplicity in this mesh chat
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            val node = Node(
                id = endpointId,
                name = info.endpointName,
                deviceModel = "Unknown",
                lastSeenMilli = System.currentTimeMillis(),
                isConnected = true,
                signalStrength = 3
            )
            scope.launch {
                chatDao.insertNode(node)
                _connectedEndpoints.value += endpointId
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    // Connected successfully
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    // Rejected
                    scope.launch {
                        _connectedEndpoints.value -= endpointId
                        val node = chatDao.getNodeById(endpointId)
                        node?.let { chatDao.updateNode(it.copy(isConnected = false)) }
                    }
                }
                else -> {
                    scope.launch {
                        _connectedEndpoints.value -= endpointId
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            scope.launch {
                _connectedEndpoints.value -= endpointId
                val node = chatDao.getNodeById(endpointId)
                node?.let { chatDao.updateNode(it.copy(isConnected = false)) }
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val currentNode = Node(
                id = endpointId,
                name = info.endpointName,
                deviceModel = "Unknown",
                lastSeenMilli = System.currentTimeMillis(),
                isConnected = false,
                signalStrength = 2
            )
            _discoveredEndpoints.value = _discoveredEndpoints.value + currentNode
        }

        override fun onEndpointLost(endpointId: String) {
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
                    if (action == "MESSAGE") {
                        val messageId = json.getString("id")
                        val content = json.getString("content")
                        val replyToId = json.optString("replyToId", null).takeIf { it.isNotEmpty() }
                        val senderId = endpointId
                        
                        val msg = Message(
                            id = messageId,
                            senderId = senderId,
                            receiverId = "me", // Assuming received to my device
                            content = content,
                            timestamp = System.currentTimeMillis(),
                            type = MessageType.TEXT,
                            status = MessageStatus.DELIVERED,
                            isFromMe = false,
                            replyToId = replyToId
                        )
                        scope.launch {
                            chatDao.insertMessage(msg)
                            // Send read receipt if active, but let's just send delivered receipt
                            sendReceipt(endpointId, messageId, "DELIVERED")
                        }
                    } else if (action == "RECEIPT") {
                        val messageId = json.getString("messageId")
                        val statusStr = json.getString("status")
                        val status = MessageStatus.valueOf(statusStr)
                        scope.launch {
                            val msg = chatDao.getMessageById(messageId)
                            if (msg != null && msg.status < status) {
                                chatDao.updateMessage(msg.copy(status = status))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NearbyManager", "Failed to parse payload", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
    
    // Commands
    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myEndpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d("NearbyManager", "Advertising started")
        }.addOnFailureListener { e ->
            Log.e("NearbyManager", "Advertising failed", e)
        }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d("NearbyManager", "Discovery started")
        }.addOnFailureListener { e ->
            Log.e("NearbyManager", "Discovery failed", e)
        }
    }
    
    fun connectToEndpoint(endpointId: String) {
        connectionsClient.requestConnection(myEndpointName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d("NearbyManager", "Connection requested")
            }.addOnFailureListener { e ->
                Log.e("NearbyManager", "Connection request failed", e)
            }
    }

    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
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
        val json = JSONObject().apply {
            put("action", "RECEIPT")
            put("messageId", messageId)
            put("status", status)
        }
        val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
    }
    
    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
