package com.example.data.repository

import com.example.data.local.ChatDao
import com.example.data.model.Message
import com.example.data.model.Node
import com.example.nearby.NearbyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(
    private val chatDao: ChatDao,
    val nearbyManager: NearbyManager
) {

    val allNodes: Flow<List<Node>> = chatDao.getAllNodes()
    val discoveredNodes: Flow<List<Node>> = nearbyManager.discoveredEndpoints
    val connectedEndpoints: Flow<Set<String>> = nearbyManager.connectedEndpoints
    
    fun getMessagesForNode(nodeId: String): Flow<List<Message>> {
        return chatDao.getMessagesForNode(nodeId)
    }

    suspend fun getNodeById(nodeId: String): Node? {
        return chatDao.getNodeById(nodeId)
    }

    suspend fun sendMessage(nodeId: String, content: String, replyToId: String? = null) {
        val msg = Message(
            senderId = "me",
            receiverId = nodeId,
            content = content,
            replyToId = replyToId
        )
        chatDao.insertMessage(msg)
        // If connected, attempt to send over Nearby
        if (nearbyManager.connectedEndpoints.value.contains(nodeId)) {
            nearbyManager.sendTextMessage(nodeId, msg)
        }
    }
    
    suspend fun sendFile(nodeId: String, uri: android.net.Uri, name: String, size: Long, mimeType: String) {
        val msg = Message(
            senderId = "me",
            receiverId = nodeId,
            content = "Sending File...",
            type = com.example.data.model.MessageType.FILE,
            fileName = name,
            fileSize = size,
            fileMimeType = mimeType,
            fileUri = uri.toString(),
            status = com.example.data.model.MessageStatus.SENDING,
            progress = 0f
        )
        chatDao.insertMessage(msg)
        if (nearbyManager.connectedEndpoints.value.contains(nodeId)) {
            nearbyManager.sendFileMessage(nodeId, msg, uri)
        }
    }
}
