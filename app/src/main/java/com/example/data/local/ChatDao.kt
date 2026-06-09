package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Message
import com.example.data.model.Node
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM nodes ORDER BY lastSeenMilli DESC")
    fun getAllNodes(): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes WHERE isContact = 1 ORDER BY username ASC")
    fun getContacts(): Flow<List<Node>>
    
    @Query("SELECT * FROM nodes WHERE id = :id LIMIT 1")
    suspend fun getNodeById(id: String): Node?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: Node)

    @Update
    suspend fun updateNode(node: Node)
    
    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun deleteNodeById(id: String)
    
    @Query("UPDATE nodes SET isConnected = 0")
    suspend fun disconnectAllNodes()

    @Query("SELECT * FROM messages WHERE (senderId = :nodeId OR receiverId = :nodeId) ORDER BY timestamp ASC")
    fun getMessagesForNode(nodeId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Update
    suspend fun updateMessage(message: Message)
    
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessage(): Flow<Message?>
    
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): Message?

    @Query("SELECT * FROM messages WHERE payloadId = :payloadId LIMIT 1")
    suspend fun getMessageByPayloadId(payloadId: Long): Message?
}
