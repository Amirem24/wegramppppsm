package com.example.nearby

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.local.ChatDao
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.MessageType
import com.example.data.model.Node
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class WebGatewayManager(
    private val context: Context,
    private val chatDao: ChatDao
) {
    private val tag = "WebGatewayManager"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _clientsCount = MutableStateFlow(0)
    val clientsCount: StateFlow<Int> = _clientsCount.asStateFlow()

    var currentPort: Int = 0
        private set
    var currentToken: String = ""
        private set

    val activeWebSockets = ConcurrentHashMap<String, Socket>()
    private val clientJobMap = ConcurrentHashMap<String, Job>()
    
    private val _typingState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typingState: StateFlow<Map<String, Boolean>> = _typingState.asStateFlow()

    fun getMyUsername(): String {
        return context.getSharedPreferences("GoChatPrefs", Context.MODE_PRIVATE)
            .getString("username", "GoChat Host") ?: "GoChat Host"
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val sAddr = addr.hostAddress ?: ""
                        if (sAddr.isNotBlank() && sAddr != "127.0.0.1") {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error detecting IP Address", e)
        }
        return "127.0.0.1"
    }

    @Synchronized
    fun startServer(): String {
        if (_isServerRunning.value) {
            return _serverUrl.value
        }

        // Try to bind on a random port between 40000 and 60000
        var port = 45000
        var successes = false
        for (i in 0..15) {
            val potentialPort = (40000..60000).random()
            try {
                serverSocket = ServerSocket(potentialPort)
                port = potentialPort
                successes = true
                break
            } catch (e: Exception) {
                Log.w(tag, "Port $potentialPort in use, retrying...")
            }
        }

        if (!successes) {
            try {
                serverSocket = ServerSocket(0) // system-allocated random fallback
                port = serverSocket?.localPort ?: 45000
            } catch (e: Exception) {
                Log.e(tag, "Failed to allocate any server socket port", e)
                return ""
            }
        }

        currentPort = port
        currentToken = "web" + (1000..9999).random().toString()
        val ip = getLocalIpAddress()
        val finalUrl = "http://$ip:$port/$currentToken"

        _serverUrl.value = finalUrl
        _isServerRunning.value = true

        serverJob = scope.launch(Dispatchers.IO) {
            Log.d(tag, "Web Access Server started successfully on port $port")
            try {
                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleTcpClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Server socket closed or stopped.")
            }
        }

        return finalUrl
    }

    @Synchronized
    fun stopServer() {
        if (!_isServerRunning.value) return
        _isServerRunning.value = false
        _serverUrl.value = ""
        _clientsCount.value = 0

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null

        // Disconnect all guest clients websockets
        activeWebSockets.forEach { (guestId, socket) ->
            try {
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            scope.launch {
                val node = chatDao.getNodeById(guestId)
                if (node != null) {
                    chatDao.updateNode(node.copy(isConnected = false))
                }
            }
        }
        activeWebSockets.clear()

        clientJobMap.forEach { (_, job) -> job.cancel() }
        clientJobMap.clear()
        _typingState.value = emptyMap()
        ttlJob?.cancel()
        ttlJob = null
    }

    private var ttlJob: Job? = null

    fun setTTL(minutes: Int) {
        ttlJob?.cancel()
        if (minutes <= 0) return
        ttlJob = scope.launch {
            delay(minutes * 60 * 1000L)
            stopServer()
        }
    }

    private suspend fun handleTcpClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val headerResult = readHttpRequestHeader(input)
            val firstLine = headerResult.first
            val headers = headerResult.second

            if (firstLine.isBlank()) {
                socket.close()
                return
            }

            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0]
            val pathWithQuery = parts[1]
            val hasQuery = pathWithQuery.contains("?")
            val path = if (hasQuery) pathWithQuery.substringBefore("?") else pathWithQuery
            val query = if (hasQuery) pathWithQuery.substringAfter("?") else ""

            // Parse path segment security token
            val requestedToken = path.trim('/').substringBefore("/")

            if (headers["upgrade"]?.lowercase() == "websocket") {
                // WebSocket upgradable handshake request
                val queryParams = parseQueryParams(query)
                val tokenFromQuery = queryParams["token"] ?: ""
                
                if (tokenFromQuery == currentToken || requestedToken == currentToken || query.contains(currentToken)) {
                    val clientKey = headers["sec-websocket-key"]
                    if (clientKey != null) {
                        val acceptKey = getWebSocketAcceptKey(clientKey)
                        val out = socket.getOutputStream()
                        val handshakeResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
                        out.write(handshakeResponse.toByteArray(Charsets.US_ASCII))
                        out.flush()

                        handleWebSocketSession(socket)
                        return
                    }
                }
            }

            // HTTP Endpoint route controls
            if (method == "GET") {
                if (path == "/$currentToken" || path == "/$currentToken/" || path == "/") {
                    // Serve static HTML client
                    val out = socket.getOutputStream()
                    val htmlContent = getClientHtml(currentToken)
                    val bodyBytes = htmlContent.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(bodyBytes)
                    out.flush()
                } else if (path.startsWith("/download/")) {
                    // Route format: /download/messageId?token=some_token
                    val queryParams = parseQueryParams(query)
                    val token = queryParams["token"] ?: ""
                    if (token == currentToken) {
                        val messageId = path.substringAfter("/download/").trim('/')
                        val messageObj = chatDao.getMessageById(messageId)
                        if (messageObj != null && messageObj.fileUri != null) {
                            try {
                                val uri = Uri.parse(messageObj.fileUri)
                                val fileStream = context.contentResolver.openInputStream(uri)
                                if (fileStream != null) {
                                    val size = messageObj.fileSize
                                    val mime = messageObj.fileMimeType ?: "application/octet-stream"
                                    val output = socket.getOutputStream()
                                    val header = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: $mime\r\n" +
                                            "Content-Length: $size\r\n" +
                                            "Content-Disposition: attachment; filename=\"${messageObj.fileName}\"\r\n" +
                                            "Connection: close\r\n\r\n"
                                    output.write(header.toByteArray(Charsets.US_ASCII))

                                    val buffer = ByteArray(32 * 1024)
                                    var readBytes = fileStream.read(buffer)
                                    while (readBytes != -1) {
                                        output.write(buffer, 0, readBytes)
                                        readBytes = fileStream.read(buffer)
                                    }
                                    output.flush()
                                    fileStream.close()
                                } else {
                                    sendHttpError(socket, 404, "File not found")
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Download failed", e)
                                sendHttpError(socket, 500, "Error reading file")
                            }
                        } else {
                            sendHttpError(socket, 404, "Message or file uri not found")
                        }
                    } else {
                        sendHttpError(socket, 401, "Unauthorized token")
                    }
                } else {
                    sendHttpError(socket, 404, "Page Not Found")
                }
            } else if (method == "POST" && path == "/upload") {
                val queryParams = parseQueryParams(query)
                val token = queryParams["token"] ?: ""
                if (token == currentToken) {
                    val encodedFileName = queryParams["fileName"] ?: "uploaded_file"
                    val fileName = URLDecoder.decode(encodedFileName, "UTF-8")
                    val guestId = queryParams["guestId"] ?: "web_browser"
                    val mimeType = queryParams["mimeType"] ?: "application/octet-stream"
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

                    try {
                        val outDir = File(context.cacheDir, "web_received")
                        if (!outDir.exists()) outDir.mkdirs()

                        val receivedFile = File(outDir, "${System.currentTimeMillis()}_$fileName")
                        val fos = FileOutputStream(receivedFile)
                        val bytesBuffer = ByteArray(32 * 1024)
                        var totalCopied = 0L

                        while (totalCopied < contentLength) {
                            val remaining = contentLength - totalCopied
                            val toRead = if (remaining > bytesBuffer.size) bytesBuffer.size else remaining.toInt()
                            val chunkReadResult = input.read(bytesBuffer, 0, toRead)
                            if (chunkReadResult == -1) break
                            fos.write(bytesBuffer, 0, chunkReadResult)
                            totalCopied += chunkReadResult
                        }
                        fos.flush()
                        fos.close()

                        // Create standard file database message entry
                        val msgType = when {
                            mimeType.startsWith("image/") -> MessageType.IMAGE
                            mimeType.startsWith("video/") -> MessageType.VIDEO
                            mimeType.startsWith("audio/") -> MessageType.AUDIO
                            else -> MessageType.FILE
                        }

                        val dbMessage = Message(
                            senderId = guestId,
                            receiverId = "me",
                            content = "Uploaded attachment: $fileName",
                            type = msgType,
                            status = MessageStatus.DELIVERED,
                            isFromMe = false,
                            fileUri = Uri.fromFile(receivedFile).toString(),
                            fileName = fileName,
                            fileSize = contentLength,
                            fileMimeType = mimeType,
                            hasAccepted = true
                        )
                        chatDao.insertMessage(dbMessage)

                        // Send success JSON answer
                        val out = socket.getOutputStream()
                        val responseJson = JSONObject().apply {
                            put("status", "success")
                            put("messageId", dbMessage.id)
                        }
                        val bodyBytes = responseJson.toString().toByteArray(Charsets.UTF_8)
                        val httpHeader = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${bodyBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(httpHeader.toByteArray(Charsets.US_ASCII))
                        out.write(bodyBytes)
                        out.flush()
                    } catch (e: Exception) {
                        Log.e(tag, "File upload failed", e)
                        sendHttpError(socket, 500, "Upload processing error")
                    }
                } else {
                    sendHttpError(socket, 401, "Unauthorized upload")
                }
            } else {
                sendHttpError(socket, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            Log.e(tag, "Client processing error", e)
        } finally {
            try {
                if (socket.isClosed == false && socket.keepAlive == false) socket.close()
            } catch (e: Exception) {}
        }
    }

    private fun sendHttpError(socket: Socket, code: Int, message: String) {
        try {
            val out = socket.getOutputStream()
            val text = "$code - $message"
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${textBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(textBytes)
            out.flush()
            socket.close()
        } catch (e: Exception) {}
    }

    private fun readHttpRequestHeader(inputStream: InputStream): Pair<String, Map<String, String>> {
        val headerBuilder = StringBuilder()
        val lastFour = ByteArray(4)
        var index = 0
        try {
            while (true) {
                val b = inputStream.read()
                if (b == -1) break
                headerBuilder.append(b.toChar())

                lastFour[index % 4] = b.toByte()
                index++
                if (index >= 4) {
                    val b0 = lastFour[index % 4]
                    val b1 = lastFour[(index + 1) % 4]
                    val b2 = lastFour[(index + 2) % 4]
                    val b3 = lastFour[(index + 3) % 4]
                    if (b0 == '\r'.toByte() && b1 == '\n'.toByte() && b2 == '\r'.toByte() && b3 == '\n'.toByte()) {
                        break
                    }
                }
                if (headerBuilder.length > 8192) { // safety limit
                    break
                }
            }
        } catch (e: Exception) {}

        val headerStr = headerBuilder.toString()
        val lines = headerStr.split("\r\n").filter { it.isNotBlank() }
        val firstLine = lines.getOrNull(0) ?: ""
        val headersMap = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(":")
            if (colon != -1) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headersMap[key] = value
            }
        }
        return Pair(firstLine, headersMap)
    }

    private fun parseQueryParams(queryString: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (queryString.isBlank()) return params
        try {
            val pairs = queryString.split("&")
            for (pair in pairs) {
                val idx = pair.indexOf("=")
                if (idx != -1) {
                    val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    params[key] = value
                } else {
                    val key = URLDecoder.decode(pair, "UTF-8")
                    params[key] = ""
                }
            }
        } catch (e: Exception) {}
        return params
    }

    private fun getWebSocketAcceptKey(clientKey: String): String {
        return try {
            val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val md = MessageDigest.getInstance("SHA-1")
            val sha1Bytes = md.digest((clientKey.trim() + magic).toByteArray(Charsets.US_ASCII))
            Base64.encodeToString(sha1Bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    private fun handleWebSocketSession(socket: Socket) {
        var guestId = ""
        val outStream = socket.getOutputStream()

        val activeJob = scope.launch(Dispatchers.IO) {
            try {
                _clientsCount.value = activeWebSockets.size + 1
                val inputStream = socket.getInputStream()
                while (true) {
                    val incoming = readWebSocketFrame(inputStream) ?: break
                    val packet = JSONObject(incoming)
                    val action = packet.optString("action")

                    when (action) {
                        "handshake" -> {
                            val username = packet.optString("username", "Web Guest")
                            guestId = packet.optString("guestId", "guest_" + (1000..9999).random().toString())

                            // Register socket
                            activeWebSockets[guestId] = socket
                            _clientsCount.value = activeWebSockets.size

                            val guestNode = Node(
                                id = guestId,
                                name = username,
                                deviceModel = "Web Browser Client",
                                lastSeenMilli = System.currentTimeMillis(),
                                isConnected = true,
                                signalStrength = 4,
                                transportType = "Web Gateway",
                                ipAddress = socket.inetAddress?.hostAddress ?: "127.0.0.1",
                                username = username,
                                avatarRef = "avatar_4",
                                isContact = true,
                                connectionState = "Connected",
                                pairStatus = "Paired"
                            )
                            chatDao.insertNode(guestNode)

                            // Send Handshake ACK
                            val ack = JSONObject().apply {
                                put("action", "handshake_ack")
                                put("myUsername", getMyUsername())
                            }
                            writeWebSocketTextFrame(outStream, ack.toString())

                            // Launch local state updates pipeline loop for this specific node
                            launchMessageObserver(guestId, socket)
                        }

                        "message" -> {
                            val content = packet.optString("content", "")
                            val clientToken = packet.optString("guestId", guestId)
                            val replyToId = packet.optString("replyToId", "").takeIf { it.isNotEmpty() }

                            if (content.isNotBlank()) {
                                val dbMessage = Message(
                                    senderId = clientToken,
                                    receiverId = "me",
                                    content = content,
                                    isFromMe = false,
                                    replyToId = replyToId,
                                    status = MessageStatus.DELIVERED
                                )
                                chatDao.insertMessage(dbMessage)
                            }
                        }

                        "typing" -> {
                            val clientToken = packet.optString("guestId", guestId)
                            val isTyping = packet.optBoolean("isTyping", false)
                            _typingState.value = _typingState.value.toMutableMap().apply {
                                this[clientToken] = isTyping
                            }
                        }

                        "read" -> {
                            val msgId = packet.optString("messageId", "")
                            if (msgId.isNotEmpty()) {
                                val dbMessage = chatDao.getMessageById(msgId)
                                if (dbMessage != null && dbMessage.isFromMe) {
                                    chatDao.updateMessage(dbMessage.copy(status = MessageStatus.READ))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "WebSocket receiver loop crash", e)
            } finally {
                if (guestId.isNotEmpty()) {
                    activeWebSockets.remove(guestId)
                    _clientsCount.value = activeWebSockets.size

                    val node = chatDao.getNodeById(guestId)
                    if (node != null) {
                        chatDao.updateNode(node.copy(isConnected = false))
                    }
                    _typingState.value = _typingState.value.toMutableMap().apply {
                        this.remove(guestId)
                    }
                }
                try {
                    socket.close()
                } catch (e: Exception) {}
            }
        }

        if (guestId.isNotEmpty()) {
            clientJobMap[guestId]?.cancel()
            clientJobMap[guestId] = activeJob
        }
    }

    private fun CoroutineScope.launchMessageObserver(guestId: String, socket: Socket) {
        launch(Dispatchers.IO) {
            val out = socket.getOutputStream()
            try {
                chatDao.getMessagesForNode(guestId).collect { list ->
                    // 1. Send entire list elements payload to sync dynamic view
                    val array = JSONArray()
                    for (msg in list) {
                        val senderName = if (msg.isFromMe) getMyUsername() else "You"
                        val jsonItem = JSONObject().apply {
                            put("id", msg.id)
                            put("senderId", msg.senderId)
                            put("receiverId", msg.receiverId)
                            put("content", msg.content)
                            put("timestamp", msg.timestamp)
                            put("isFromMe", msg.isFromMe) // true/false flag from app perspective
                            put("status", msg.status.name)
                            put("type", msg.type.name)
                            put("fileName", msg.fileName ?: "")
                            put("fileSize", msg.fileSize)
                            put("fileMimeType", msg.fileMimeType ?: "")
                            put("replyToId", msg.replyToId ?: "")
                            if (msg.type == MessageType.FILE || msg.type == MessageType.IMAGE || msg.type == MessageType.VIDEO || msg.type == MessageType.AUDIO) {
                                val downloadUrl = "http://${getLocalIpAddress()}:$currentPort/download/${msg.id}?token=$currentToken"
                                put("downloadUrl", downloadUrl)
                            }
                        }
                        array.put(jsonItem)
                    }

                    val payload = JSONObject().apply {
                        put("action", "history")
                        put("messages", array)
                    }
                    writeWebSocketTextFrame(out, payload.toString())

                    // 2. Clear out any outgoing "SENT" messages automatically by setting status to DELIVERED
                    val unsent = list.filter { it.senderId == "me" && it.status == MessageStatus.SENT }
                    for (msg in unsent) {
                        chatDao.updateMessage(msg.copy(status = MessageStatus.DELIVERED))
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "History sync loop exception on $guestId", e)
            }
        }
    }

    private fun readWebSocketFrame(inputStream: java.io.InputStream): String? {
        val b0 = inputStream.read()
        if (b0 == -1) return null
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        if (opcode == 8) { // CLOSE opcode
            return null
        }

        val b1 = inputStream.read()
        if (b1 == -1) return null
        val masked = (b1 and 0x80) != 0
        val baseLen = b1 and 0x7F

        var payloadLen = baseLen.toLong()
        if (baseLen == 126) {
            val lenBytes = ByteArray(2)
            inputStream.read(lenBytes)
            payloadLen = (((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)).toLong()
        } else if (baseLen == 127) {
            val lenBytes = ByteArray(8)
            inputStream.read(lenBytes)
            var tempLen = 0L
            for (i in 0..7) {
                tempLen = (tempLen shl 8) or (lenBytes[i].toInt() and 0xFF).toLong()
            }
            payloadLen = tempLen
        }

        val maskKey = ByteArray(4)
        if (masked) {
            inputStream.read(maskKey)
        }

        val payload = ByteArray(payloadLen.toInt())
        var readSoFar = 0
        while (readSoFar < payloadLen) {
            val read = inputStream.read(payload, readSoFar, (payloadLen - readSoFar).toInt())
            if (read == -1) return null
            readSoFar += read
        }

        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return if (opcode == 1) String(payload, Charsets.UTF_8) else ""
    }

    private fun writeWebSocketTextFrame(outputStream: java.io.OutputStream, message: String) {
        val payloadBytes = message.toByteArray(Charsets.UTF_8)
        outputStream.write(0x81) // FIN & Text opcode

        val len = payloadBytes.size
        if (len <= 125) {
            outputStream.write(len)
        } else if (len <= 65535) {
            outputStream.write(126)
            outputStream.write((len shr 8) and 0xFF)
            outputStream.write(len and 0xFF)
        } else {
            outputStream.write(127)
            outputStream.write(0)
            outputStream.write(0)
            outputStream.write(0)
            outputStream.write(0)
            outputStream.write((len shr 24) and 0xFF)
            outputStream.write((len shr 16) and 0xFF)
            outputStream.write((len shr 8) and 0xFF)
            outputStream.write(len and 0xFF)
        }
        outputStream.write(payloadBytes)
        outputStream.flush()
    }

    private fun getClientHtml(token: String): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>GoChat Web Space</title>
    <style>
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }
        body {
            background-color: #121214;
            color: #e4e6eb;
            display: flex;
            flex-direction: column;
            height: 100vh;
        }
        header {
            background-color: #1e1e24;
            padding: 15px 20px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            border-bottom: 1px solid #2a2a32;
            box-shadow: 0 4px 6px -1px rgba(0,0,0,0.2);
        }
        .header-title-container {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .app-badge {
            background: linear-gradient(135deg, #7c4dff, #6200ee);
            color: #ffffff;
            font-size: 11px;
            font-weight: 800;
            padding: 4px 8px;
            border-radius: 6px;
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }
        .header-info h1 {
            font-size: 18px;
            font-weight: 700;
            color: #ffffff;
        }
        .header-info span {
            font-size: 12px;
            color: #9da4b0;
        }
        .status-badge {
            display: flex;
            align-items: center;
            gap: 8px;
            background-color: #24242e;
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 13px;
            border: 1px solid #323242;
        }
        .status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background-color: #ff4d4d;
        }
        .status-dot.online {
            background-color: #4caf50;
            box-shadow: 0 0 8px #4caf50;
        }
        main {
            flex: 1;
            display: flex;
            flex-direction: column;
            overflow: hidden;
            max-width: 800px;
            width: 100%;
            margin: 0 auto;
            position: relative;
        }
        #messages-container {
            flex: 1;
            padding: 20px;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 16px;
            background-color: #121214;
        }
        .message-row {
            display: flex;
            flex-direction: column;
            max-width: 80%;
            width: fit-content;
        }
        .message-row.me {
            align-self: flex-end;
            align-items: flex-end;
        }
        .message-row.other {
            align-self: flex-start;
            align-items: flex-start;
        }
        .msg-sender-name {
            font-size: 11px;
            color: #9da4b0;
            margin-bottom: 4px;
            font-weight: 600;
        }
        .message-bubble {
            padding: 12px 16px;
            border-radius: 18px;
            font-size: 15px;
            line-height: 1.4;
            word-break: break-word;
            position: relative;
            box-shadow: 0 1px 2px rgba(0,0,0,0.15);
        }
        .message-row.me .message-bubble {
            background-color: #7c4dff;
            color: #ffffff;
            border-bottom-right-radius: 4px;
        }
        .message-row.other .message-bubble {
            background-color: #1e1e24;
            color: #e4e6eb;
            border-bottom-left-radius: 4px;
            border: 1px solid #2a2a32;
        }
        .message-reply-quote {
            background-color: rgba(255,255,255,0.08);
            border-left: 3px solid #7c4dff;
            padding: 6px 10px;
            border-radius: 6px;
            font-size: 12px;
            margin-bottom: 8px;
            color: #ced4da;
            max-width: 100%;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .message-row.me .message-reply-quote {
            border-left-color: #fff;
        }
        .msg-time {
            font-size: 10px;
            color: #757d8a;
            margin-top: 4px;
            display: flex;
            align-items: center;
            gap: 4px;
        }
        .reply-action-btn {
            background: none;
            border: none;
            color: #a0aec0;
            cursor: pointer;
            font-size: 11px;
            margin-top: 4px;
            display: none;
            align-items: center;
            gap: 3px;
        }
        .message-row:hover .reply-action-btn {
            display: inline-flex;
        }
        .reply-action-btn:hover {
            color: #7c4dff;
        }
        .attachment-card {
            display: flex;
            align-items: center;
            gap: 12px;
            background-color: rgba(0,0,0,0.2);
            padding: 10px;
            border-radius: 12px;
            margin-top: 6px;
            border: 1px solid rgba(255,255,255,0.05);
        }
        .attachment-icon {
            background: #2d2d3a;
            padding: 8px;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .attachment-meta {
            flex: 1;
            min-width: 0;
        }
        .attachment-name {
            font-size: 13px;
            font-weight: 600;
            color: #fff;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .attachment-size {
            font-size: 11px;
            color: #a0aec0;
        }
        .btn-download {
            background: #7c4dff;
            color: white;
            border: none;
            padding: 6px 12px;
            border-radius: 6px;
            font-size: 11px;
            font-weight: bold;
            cursor: pointer;
            text-decoration: none;
            display: inline-block;
        }
        .btn-download:hover {
            background: #6200ee;
        }
        #typing-indicator {
            padding: 8px 24px;
            font-size: 12px;
            color: #a0aec0;
            font-style: italic;
            height: 30px;
            display: flex;
            align-items: center;
            gap: 4px;
        }
        .typing-dots span {
            width: 4px;
            height: 4px;
            border-radius: 50%;
            background-color: #a0aec0;
            display: inline-block;
            animation: typingDelay 1s infinite alternate;
        }
        .typing-dots span:nth-child(2) { animation-delay: 0.2s; }
        .typing-dots span:nth-child(3) { animation-delay: 0.4s; }
        @keyframes typingDelay {
            0% { transform: translateY(0); opacity: 0.3; }
            100% { transform: translateY(-3px); opacity: 1; }
        }
        #reply-panel {
            background-color: #1e1e24;
            padding: 10px 20px;
            border-left: 4px solid #7c4dff;
            display: none;
            align-items: center;
            justify-content: space-between;
            border-bottom: 1px solid #2a2a32;
        }
        #reply-panel .reply-title {
            font-size: 12px;
            font-weight: bold;
            color: #7c4dff;
        }
        #reply-panel .reply-content {
            font-size: 13px;
            color: #a0aec0;
            text-overflow: ellipsis;
            white-space: nowrap;
            overflow: hidden;
            max-width: 80%;
        }
        #reply-panel .btn-close-reply {
            background: none;
            border: none;
            color: #a0aec0;
            cursor: pointer;
        }
        input-area {
            background-color: #1e1e24;
            padding: 15px 20px;
            display: flex;
            align-items: center;
            gap: 12px;
            border-top: 1px solid #2a2a32;
        }
        .input-bar-controls {
            flex: 1;
            display: flex;
            align-items: center;
            background-color: #121214;
            padding: 6px 16px;
            border-radius: 28px;
            border: 1px solid #2d2d3a;
        }
        .input-bar-controls input {
            flex: 1;
            background: none;
            border: none;
            outline: none;
            color: #fff;
            padding: 8px 0;
            font-size: 15px;
        }
        .btn-attach {
            background: none;
            border: none;
            color: #a0aec0;
            cursor: pointer;
            margin-right: 12px;
            display: flex;
            align-items: center;
        }
        .btn-attach:hover {
            color: #7c4dff;
        }
        .btn-send {
            background-color: #7c4dff;
            border: none;
            color: white;
            padding: 10px;
            border-radius: 50%;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            transition: all 0.2s;
            width: 42px;
            height: 42px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.2);
        }
        .btn-send:hover {
            background-color: #6200ee;
            transform: scale(1.04);
        }
        #progress-overlay {
            position: absolute;
            bottom: 74px;
            left: 20px;
            right: 20px;
            background: rgba(30, 30, 36, 0.95);
            border: 1px solid #3e3e4f;
            border-radius: 12px;
            padding: 15px;
            display: none;
            z-index: 100;
        }
        .pb-wrapper {
            width: 100%;
            background-color: #242430;
            height: 6px;
            border-radius: 3px;
            margin-top: 8px;
            overflow: hidden;
        }
        .pb-fill {
            background-color: #7c4dff;
            height: 100%;
            width: 0%;
            transition: width 0.1s;
        }
        #setup-panel {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(18, 18, 20, 0.98);
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 1000;
            padding: 20px;
        }
        .setup-card {
            background-color: #1e1e24;
            border: 1px solid #30303e;
            padding: 30px;
            border-radius: 16px;
            max-width: 400px;
            width: 100%;
            text-align: center;
            box-shadow: 0 10px 25px -5px rgba(0,0,0,0.4);
        }
        .setup-card h2 {
            margin-bottom: 12px;
            color: #ffffff;
        }
        .setup-card p {
            font-size: 14px;
            color: #a0aec0;
            margin-bottom: 24px;
        }
        .setup-card input {
            width: 100%;
            padding: 12px 16px;
            border-radius: 10px;
            border: 1px solid #323242;
            background-color: #121214;
            color: #fff;
            outline: none;
            font-size: 15px;
            margin-bottom: 18px;
            text-align: center;
        }
        .setup-card input:focus {
            border-color: #7c4dff;
        }
        .btn-primary {
            background: linear-gradient(135deg, #7c4dff, #6200ee);
            color: #fff;
            border: none;
            padding: 12px 24px;
            border-radius: 10px;
            font-size: 15px;
            font-weight: bold;
            width: 100%;
            cursor: pointer;
            transition: opacity 0.2s;
        }
        .btn-primary:hover {
            opacity: 0.9;
        }
    </style>
</head>
<body>

    <div id="setup-panel">
        <div class="setup-card">
            <h2>Welcome to GoChat Space</h2>
            <p>Choose an elegant screen name to securely join this active local session now.</p>
            <input type="text" id="username-input" placeholder="Your Display Name" value="Guest Web Peer">
            <button class="btn-primary" onclick="completeSetup()">Connect to host</button>
        </div>
    </div>

    <header>
        <div class="header-title-container">
            <div class="app-badge">GoChat Lane</div>
            <div class="header-info">
                <h1 id="host-name">GoChat Session</h1>
                <span>LAN Browser Access</span>
            </div>
        </div>
        <div class="status-badge">
            <div class="status-dot" id="status-dot"></div>
            <span id="status-text">Disconnected</span>
        </div>
    </header>

    <main>
        <div id="messages-container">
            <!-- Messages render dynamically here -->
        </div>

        <div id="typing-indicator" style="visibility: hidden;">
            <span id="typing-username">Host</span> is typing
            <div class="typing-dots">
                <span></span><span></span><span></span>
            </div>
        </div>

        <div id="progress-overlay">
            <div style="font-size:12px; font-weight:bold; color: #fff; display:flex; justify-content:space-between;">
                <span id="upload-status-text">Uploading File...</span>
                <span id="upload-percentage">0%</span>
            </div>
            <div class="pb-wrapper">
                <div class="pb-fill" id="upload-pb-fill"></div>
            </div>
        </div>

        <div id="reply-panel">
            <div>
                <div class="reply-title" id="reply-title">Replying to msg</div>
                <div class="reply-content" id="reply-content">Message content goes here...</div>
            </div>
            <button class="btn-close-reply" onclick="cancelReply()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>

        <input-area>
            <div class="input-bar-controls">
                <button class="btn-attach" onclick="triggerFilePicker()">
                     <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-dasharray="2" stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"></path></svg>
                </button>
                <input type="text" id="message-input" placeholder="Type messages here..." onkeypress="handleKeyPress(event)" oninput="notifyTyping()">
            </div>
            <button class="btn-send" onclick="performSend()">
                 <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="transform: rotate(45deg); margin-left:-3px; margin-top:-1px;"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>
            </button>
        </input-area>
        <input type="file" id="file-picker" style="display: none;" onchange="handleFileSelected(event)">
    </main>

    <script>
        const token = "$token";
        let guestId = "web_" + Math.random().toString(36).substr(2, 9);
        let username = "Guest Web Peer";
        let ws = null;
        let activeReplyToId = "";
        let hostName = "Local Host";
        let isTyping = false;
        let typingTimeout = null;

        // Automatically load previously stored screen name
        const storedName = localStorage.getItem("gochat_web_username");
        if (storedName) {
            document.getElementById("username-input").value = storedName;
        }

        function completeSetup() {
            const val = document.getElementById("username-input").value.trim();
            if (val) {
                username = val;
                localStorage.setItem("gochat_web_username", val);
                document.getElementById("setup-panel").style.display = "none";
                initWebSocket();
            }
        }

        function initWebSocket() {
            const loc = window.location;
            let wsUri = "ws://" + loc.host + "/websocket?token=" + token + "&guestId=" + guestId;
            // Support fallback url schemes if needed
            if (loc.protocol === "https:") {
                wsUri = "wss://" + loc.host + "/websocket?token=" + token + "&guestId=" + guestId;
            }

            ws = new WebSocket(wsUri);

            ws.onopen = function() {
                document.getElementById("status-dot").className = "status-dot online";
                document.getElementById("status-text").innerText = "Connected";
                
                // Send Handshake packet
                ws.send(JSON.stringify({
                    action: "handshake",
                    username: username,
                    guestId: guestId
                }));
            };

            ws.onclose = function() {
                document.getElementById("status-dot").className = "status-dot";
                document.getElementById("status-text").innerText = "Disconnected";
                setTimeout(initWebSocket, 2000); // auto reconnect
            };

            ws.onmessage = function(event) {
                try {
                    const data = JSON.parse(event.data);
                    const action = data.action;

                    if (action === "handshake_ack") {
                        hostName = data.myUsername;
                        document.getElementById("host-name").innerText = hostName;
                    } else if (action === "history") {
                        renderMessages(data.messages);
                        
                        // Mark final message as read automatically
                        if (data.messages && data.messages.length > 0) {
                            const lastMsg = data.messages[data.messages.length - 1];
                            if (lastMsg.senderId === "me") {
                                ws.send(JSON.stringify({
                                    action: "read",
                                    messageId: lastMsg.id,
                                    guestId: guestId
                                }));
                            }
                        }
                    } else if (action === "typing") {
                        updateTypingState(data.isTyping);
                    }
                } catch (e) {
                    console.error("ws packet failure", e);
                }
            };
        }

        function renderMessages(messages) {
            const container = document.getElementById("messages-container");
            const prevScroll = container.scrollHeight - container.scrollTop;
            container.innerHTML = "";

            messages.forEach(msg => {
                const isMe = msg.isFromMe; // app sends history matching this exact flag structure
                const row = document.createElement("div");
                row.className = "message-row " + (isMe ? "me" : "other");

                const senderNameEl = document.createElement("div");
                senderNameEl.className = "msg-sender-name";
                senderNameEl.innerText = isMe ? hostName : "You";
                row.appendChild(senderNameEl);

                const bubble = document.createElement("div");
                bubble.className = "message-bubble";

                // Replying quote box
                if (msg.replyToId) {
                    const parentMsg = messages.find(m => m.id === msg.replyToId);
                    if (parentMsg) {
                        const quote = document.createElement("div");
                        quote.className = "message-reply-quote";
                        quote.innerText = "Reply: " + parentMsg.content;
                        bubble.appendChild(quote);
                    }
                }

                // File Attachment element
                if (msg.type !== "TEXT") {
                    const attach = document.createElement("div");
                    attach.className = "attachment-card";

                    const icon = document.createElement("div");
                    icon.className = "attachment-icon";
                    icon.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#7c4dff" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>';
                    attach.appendChild(icon);

                    const meta = document.createElement("div");
                    meta.className = "attachment-meta";
                    const name = document.createElement("div");
                    name.className = "attachment-name";
                    name.innerText = msg.fileName || "File Attachment";
                    meta.appendChild(name);

                    const size = document.createElement("div");
                    size.className = "attachment-size";
                    size.innerText = formatBytes(msg.fileSize);
                    meta.appendChild(size);
                    attach.appendChild(meta);

                    if (msg.downloadUrl) {
                        const dl = document.createElement("a");
                        dl.className = "btn-download";
                        dl.href = msg.downloadUrl;
                        dl.innerText = "Get File";
                        dl.target = "_blank";
                        attach.appendChild(dl);
                    }
                    bubble.appendChild(attach);
                } else {
                    const content = document.createElement("div");
                    content.innerText = msg.content;
                    bubble.appendChild(content);
                }

                row.appendChild(bubble);

                const footer = document.createElement("div");
                footer.className = "msg-time";
                const date = new Date(msg.timestamp);
                footer.innerText = date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
                
                // Add status marks details (ticks)
                if (!isMe) { // Web is sender, see ticks status details
                    const ticks = document.createElement("span");
                    ticks.style.marginLeft = "4px";
                    if (msg.status === "READ") {
                        ticks.innerHTML = '✔✔';
                        ticks.style.color = '#4caf50';
                    } else if (msg.status === "DELIVERED") {
                        ticks.innerHTML = '✔✔';
                        ticks.style.color = '#a0aec0';
                    } else if (msg.status === "SENT") {
                        ticks.innerHTML = '✔';
                        ticks.style.color = '#a0aec0';
                    } else {
                        ticks.innerHTML = '⏳';
                    }
                    footer.appendChild(ticks);
                }
                
                row.appendChild(footer);

                // Add Reply button option
                const replyBtn = document.createElement("button");
                replyBtn.className = "reply-action-btn";
                replyBtn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 17 4 12 9 7"></polyline><path d="M20 18v-2a4 4 0 0 0-4-4H4"></path></svg> Reply';
                replyBtn.onclick = function() {
                    startReply(msg);
                };
                row.appendChild(replyBtn);

                container.appendChild(row);
            });

            // Smart auto-scroll downwards
            container.scrollTop = container.scrollHeight;
        }

        function startReply(msg) {
            activeReplyToId = msg.id;
            document.getElementById("reply-title").innerText = "Replying to " + (msg.isFromMe ? hostName : "yourself");
            document.getElementById("reply-content").innerText = msg.content;
            document.getElementById("reply-panel").style.display = "flex";
            document.getElementById("message-input").focus();
        }

        function cancelReply() {
            activeReplyToId = "";
            document.getElementById("reply-panel").style.display = "none";
        }

        function handleKeyPress(e) {
            if (e.key === 'Enter') {
                performSend();
            }
        }

        function performSend() {
            const input = document.getElementById("message-input");
            const text = input.value.trim();
            if (text && ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({
                    action: "message",
                    guestId: guestId,
                    content: text,
                    replyToId: activeReplyToId
                }));
                input.value = "";
                cancelReply();
            }
        }

        let isTypingSent = false;
        function notifyTyping() {
            if (!ws || ws.readyState !== WebSocket.OPEN) return;
            if (!isTypingSent) {
                isTypingSent = true;
                ws.send(JSON.stringify({
                    action: "typing",
                    guestId: guestId,
                    isTyping: true
                }));
            }
            clearTimeout(typingTimeout);
            typingTimeout = setTimeout(() => {
                isTypingSent = false;
                ws.send(JSON.stringify({
                    action: "typing",
                    guestId: guestId,
                    isTyping: false
                }));
            }, 2500);
        }

        function updateTypingState(hostIsTyping) {
            const el = document.getElementById("typing-indicator");
            if (hostIsTyping) {
                document.getElementById("typing-username").innerText = hostName;
                el.style.visibility = "visible";
            } else {
                el.style.visibility = "hidden";
            }
        }

        function triggerFilePicker() {
            document.getElementById("file-picker").click();
        }

        function handleFileSelected(e) {
            const file = e.target.files[0];
            if (!file) return;

            const input = document.getElementById("message-input");
            const pOverlay = document.getElementById("progress-overlay");
            const pBar = document.getElementById("upload-pb-fill");
            const pText = document.getElementById("upload-percentage");

            pOverlay.style.display = "block";
            pBar.style.width = "0%";
            pText.innerText = "0%";

            const xhr = new XMLHttpRequest();
            const uploadUrl = "/upload?token=" + token + "&fileName=" + encodeURIComponent(file.name) + "&guestId=" + guestId + "&mimeType=" + encodeURIComponent(file.type);

            xhr.open("POST", uploadUrl, true);

            xhr.upload.onprogress = function(event) {
                if (event.lengthComputable) {
                    const pct = Math.round((event.loaded / event.total) * 100);
                    pBar.style.width = pct + "%";
                    pText.innerText = pct + "%";
                }
            };

            xhr.onload = function() {
                pOverlay.style.display = "none";
                if (xhr.status === 200) {
                    // Success websocket sync triggers automatically
                } else {
                    alert("Upload failed. Status: " + xhr.status);
                }
            };

            xhr.onerror = function() {
                pOverlay.style.display = "none";
                alert("An error occurred during file upload.");
            };

            xhr.send(file);
        }

        function formatBytes(bytes, decimals = 2) {
            if (bytes === 0) return '0 Bytes';
            const k = 1024;
            const dm = decimals < 0 ? 0 : decimals;
            const sizes = ['Bytes', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
        }
    </script>
</body>
</html>
"""
    }
}
