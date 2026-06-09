package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Message
import com.example.data.model.Node
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

    // Discovery state
    val discoveredNodes: StateFlow<List<Node>> = repository.discoveredNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedNodes: StateFlow<List<Node>> = repository.allNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedEndpoints: StateFlow<Set<String>> = repository.connectedEndpoints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _selectedTransport = MutableStateFlow("Auto")
    val selectedTransport: StateFlow<String> = _selectedTransport.asStateFlow()

    fun setSelectedTransport(transport: String) {
        _selectedTransport.value = transport
        stopNearby()
        startNearby()
    }

    // Start discovery and advertising
    fun startNearby() {
        val transport = _selectedTransport.value
        repository.nearbyManager.startAdvertising(transport)
        repository.nearbyManager.startDiscovery(transport)
    }
    
    fun stopNearby() {
        repository.nearbyManager.stopAll()
    }

    fun connectToNode(nodeId: String) {
        repository.nearbyManager.connectToEndpoint(nodeId)
    }

    fun disconnectNode(nodeId: String) {
        repository.nearbyManager.disconnect(nodeId)
    }

    // Get messages for a chat
    fun getMessagesForNode(nodeId: String): StateFlow<List<Message>> {
        return repository.getMessagesForNode(nodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun sendMessage(nodeId: String, text: String, replyToId: String? = null) {
        viewModelScope.launch {
            repository.sendMessage(nodeId, text, replyToId)
        }
    }
    
    fun sendFile(nodeId: String, uri: android.net.Uri, name: String, size: Long, mimeType: String) {
        viewModelScope.launch {
            repository.sendFile(nodeId, uri, name, size, mimeType)
        }
    }
    
    // Combine discovered with saved nodes
    val aggregatedNodes = combine(savedNodes, discoveredNodes, connectedEndpoints) { saved, discovered, connected ->
        val map = saved.associateBy { it.id }.toMutableMap()
        discovered.forEach { node ->
            if (map.containsKey(node.id)) {
                map[node.id] = map[node.id]!!.copy(isConnected = connected.contains(node.id), lastSeenMilli = System.currentTimeMillis())
            } else {
                map[node.id] = node.copy(isConnected = connected.contains(node.id))
            }
        }
        map.values.toList().sortedByDescending { it.lastSeenMilli }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
