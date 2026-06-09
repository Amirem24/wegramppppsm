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
}
