package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "nodes")
data class Node(
    @PrimaryKey val id: String, // Endpoint ID from Nearby Connections
    val name: String,
    val deviceModel: String,
    val lastSeenMilli: Long,
    val isConnected: Boolean,
    val signalStrength: Int = 3, // 1-weak, 2-good, 3-strong dummy if not provided
    val transportType: String = "BLE", // Bluetooth, Wi-Fi, Hotspot
    val ipAddress: String = "192.168.1.?" // Simulated IP
)

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED,
    TRANSFERRING_FILE, PAUSED, FILE_RECEIVED, FILE_REJECTED
}

enum class MessageType {
    TEXT, FILE, IMAGE, VIDEO, AUDIO, DOCUMENT
}

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val receiverId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    val isFromMe: Boolean = true,
    val replyToId: String? = null, // For reply feature
    
    // File Transfer fields
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val fileMimeType: String? = null,
    val progress: Float = 0f,
    val speedKbps: Long = 0L,
    val estimatedTimeLeft: Long = 0L,
    val payloadId: Long? = null,
    val fileHash: String? = null
)
