package org.opentrafficmap.citstogo.bridge

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

class MiniMqttClient : Closeable {
    private var socket: java.net.Socket? = null
    private var input: DataInputStream? = null
    private var output: BufferedOutputStream? = null
    private var pingThread: Thread? = null
    @Volatile private var running = false
    private var topicPrefix = ""

    @Synchronized
    fun connect(mqttUri: String, nodeId: String, appVersion: String) {
        close()
        val cleanNode = nodeId.trim()
        require(cleanNode.isNotEmpty()) { "Node ID is empty" }
        val uri = parseUri(mqttUri)
        val tls = when (uri.scheme.lowercase(Locale.ROOT)) {
            "mqtt" -> false
            "mqtts", "ssl" -> true
            else -> throw IllegalArgumentException("Use mqtt:// or mqtts:// URI")
        }
        val host = uri.host ?: throw IllegalArgumentException("MQTT host is empty")
        val port = if (uri.port > 0) uri.port else if (tls) 8883 else 1883
        val factory: SocketFactory = if (tls) SSLSocketFactory.getDefault() else SocketFactory.getDefault()
        val s = factory.createSocket(host, port)
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = 15_000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

        topicPrefix = "its/$cleanNode/"
        val userInfo = uri.rawUserInfo?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        val username = userInfo?.substringBefore(':', missingDelimiterValue = userInfo)
        val password = userInfo?.substringAfter(':', missingDelimiterValue = "")
            ?.takeIf { userInfo.contains(':') }
        val clientId = "cits-android-${clientSafe(cleanNode)}"

        sendConnect(clientId, topicPrefix + "status", "offline".toByteArray(), username, password)
        readConnAck()
        running = true
        startPingThread()
        publish(topicPrefix + "status", "online".toByteArray(), retain = true, flush = true)
        publish(topicPrefix + "info", infoPayload(cleanNode, appVersion), retain = false, flush = true)
        publishStats(0)
    }

    @Synchronized
    fun publishPacket(payload: ByteArray) {
        checkConnected()
        publish(topicPrefix + "packet", payload, retain = false, flush = false)
    }

    @Synchronized
    fun publishStats(secondsSinceStart: Long) {
        checkConnected()
        val json = """{"rbt":$secondsSinceStart}"""
        publish(topicPrefix + "stats", json.toByteArray(), retain = false, flush = true)
    }

    @Synchronized
    fun flush() {
        output?.flush()
    }

    fun isConnected(): Boolean {
        val s = socket
        return running && s != null && s.isConnected && !s.isClosed
    }

    @Synchronized
    override fun close() {
        running = false
        runCatching {
            output?.write(0xE0)
            output?.write(0)
            output?.flush()
        }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun sendConnect(
        clientId: String,
        willTopic: String,
        willPayload: ByteArray,
        username: String?,
        password: String?,
    ) {
        val variableHeader = ByteArrayOutputStream()
        writeUtf8(variableHeader, "MQTT")
        variableHeader.write(4)
        var flags = 0x02 or 0x04 or 0x20
        if (password != null) flags = flags or 0x40
        if (username != null) flags = flags or 0x80
        variableHeader.write(flags)
        writeShort(variableHeader, KEEPALIVE_SECONDS)

        val payload = ByteArrayOutputStream()
        writeUtf8(payload, clientId)
        writeUtf8(payload, willTopic)
        writeBinary(payload, willPayload)
        if (username != null) writeUtf8(payload, username)
        if (password != null) writeUtf8(payload, password)

        val body = ByteArrayOutputStream()
        body.write(variableHeader.toByteArray())
        body.write(payload.toByteArray())
        writePacket(0x10, body.toByteArray(), flush = true)
    }

    private fun readConnAck() {
        val input = input ?: throw IOException("MQTT input is closed")
        val header = input.readUnsignedByte()
        if (header != 0x20) throw IOException("Expected CONNACK, got 0x${header.toString(16)}")
        val remaining = readRemainingLength(input)
        if (remaining != 2) throw IOException("Bad CONNACK length $remaining")
        val flags = input.readUnsignedByte()
        val code = input.readUnsignedByte()
        if (code != 0) throw IOException("MQTT CONNACK refused, code $code, flags $flags")
    }

    private fun publish(topic: String, payload: ByteArray, retain: Boolean, flush: Boolean) {
        val body = ByteArrayOutputStream()
        writeUtf8(body, topic)
        body.write(payload)
        writePacket(if (retain) 0x31 else 0x30, body.toByteArray(), flush)
    }

    private fun writePacket(fixedHeader: Int, body: ByteArray, flush: Boolean) {
        val output = output ?: throw IOException("MQTT output is closed")
        output.write(fixedHeader)
        writeRemainingLength(output, body.size)
        output.write(body)
        if (flush) output.flush()
    }

    private fun startPingThread() {
        pingThread = Thread({
            while (running) {
                try {
                    Thread.sleep(KEEPALIVE_SECONDS * 500L)
                    synchronized(this) {
                        val out = output
                        if (running && out != null) {
                            out.write(0xC0)
                            out.write(0)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                    running = false
                    break
                }
            }
        }, "mqtt-ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun checkConnected() {
        if (!isConnected()) throw IOException("MQTT is not connected")
    }

    private fun infoPayload(nodeId: String, appVersion: String): ByteArray {
        val emac = if (nodeId.matches(Regex("(?i)[0-9a-f]{12}"))) {
            nodeId.chunked(2).joinToString(":")
        } else {
            nodeId
        }
        val json = """{"emac":"${jsonEscape(emac)}","ver":"${jsonEscape(appVersion)}","hwv":"android-usb-bridge"}"""
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val KEEPALIVE_SECONDS = 60

        private fun parseUri(raw: String): URI {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("MQTT URI is empty")
            return URI.create(if ("://" in trimmed) trimmed else "mqtt://$trimmed")
        }

        private fun clientSafe(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).take(48)

        private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeShort(out, bytes.size)
            out.write(bytes)
        }

        private fun writeBinary(out: ByteArrayOutputStream, value: ByteArray) {
            writeShort(out, value.size)
            out.write(value)
        }

        private fun writeShort(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }

        private fun writeRemainingLength(out: OutputStream, value: Int) {
            var remaining = value
            do {
                var encodedByte = remaining % 128
                remaining /= 128
                if (remaining > 0) encodedByte = encodedByte or 128
                out.write(encodedByte)
            } while (remaining > 0)
        }

        private fun readRemainingLength(input: DataInputStream): Int {
            var multiplier = 1
            var value = 0
            var encodedByte: Int
            do {
                encodedByte = input.readUnsignedByte()
                value += (encodedByte and 127) * multiplier
                multiplier *= 128
                if (multiplier > 128 * 128 * 128) throw IOException("Malformed MQTT remaining length")
            } while ((encodedByte and 128) != 0)
            return value
        }

        private fun jsonEscape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
