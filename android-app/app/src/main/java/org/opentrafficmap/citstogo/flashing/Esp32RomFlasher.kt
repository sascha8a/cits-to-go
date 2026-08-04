package org.opentrafficmap.citstogo.flashing

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.ceil

interface EspFlashTransport {
    fun write(data: ByteArray)
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun setControlLines(dtr: Boolean, rts: Boolean)
}

class Esp32RomFlasher(private val transport: EspFlashTransport) {
    private val incomingBytes = ArrayDeque<Int>()

    fun flash(firmware: ByteArray, onProgress: (Float) -> Unit) {
        validateMergedImage(firmware)
        enterUsbJtagBootloader()
        sync()
        val securityInfo = command(GET_SECURITY_INFO, byteArrayOf(), timeoutMs = 3_000)
        if (securityInfo.size < 16 || securityInfo.readUInt32(12) != ESP32_C5_CHIP_ID) {
            throw IOException("Connected Espressif device is not an ESP32-C5")
        }
        command(SPI_ATTACH, ByteArray(8))

        val blockCount = ceil(firmware.size.toDouble() / FLASH_BLOCK_SIZE).toInt()
        val paddedSize = blockCount * FLASH_BLOCK_SIZE
        command(
            FLASH_BEGIN,
            words(paddedSize, blockCount, FLASH_BLOCK_SIZE, FLASH_OFFSET, 0),
            timeoutMs = eraseTimeout(firmware.size),
        )
        for (sequence in 0 until blockCount) {
            val block = ByteArray(FLASH_BLOCK_SIZE) { 0xff.toByte() }
            val sourceOffset = sequence * FLASH_BLOCK_SIZE
            val length = minOf(FLASH_BLOCK_SIZE, firmware.size - sourceOffset)
            firmware.copyInto(block, 0, sourceOffset, sourceOffset + length)
            val payload = words(FLASH_BLOCK_SIZE, sequence, 0, 0) + block
            command(FLASH_DATA, payload, checksum(block), timeoutMs = 5_000)
            onProgress((sequence + 1).toFloat() / blockCount)
        }

        val remoteMd5 = command(
            SPI_FLASH_MD5,
            words(FLASH_OFFSET, firmware.size, 0, 0),
            timeoutMs = maxOf(5_000, firmware.size / 125),
        ).toString(Charsets.US_ASCII).trim().lowercase()
        val localMd5 = MessageDigest.getInstance("MD5").digest(firmware).toHex()
        if (remoteMd5 != localMd5) throw IOException("Flash verification failed")
        runCatching { command(FLASH_END, words(0), timeoutMs = 1_000) }
    }

    private fun enterUsbJtagBootloader() {
        // Espressif's USB-Serial/JTAG reset sequence, expressed as atomic line states.
        transport.setControlLines(dtr = false, rts = false)
        Thread.sleep(100)
        transport.setControlLines(dtr = true, rts = false)
        Thread.sleep(100)
        transport.setControlLines(dtr = true, rts = true)
        transport.setControlLines(dtr = false, rts = true)
        Thread.sleep(100)
        transport.setControlLines(dtr = false, rts = false)
        Thread.sleep(100)
    }

    private fun validateMergedImage(firmware: ByteArray) {
        val header = ESP32_C5_BOOTLOADER_OFFSET
        if (firmware.size < header + 16 || firmware[header].toInt() and 0xff != ESP_IMAGE_MAGIC ||
            firmware.readUInt16(header + 12) != ESP32_C5_CHIP_ID
        ) {
            throw IOException("Release artifact is not a merged ESP32-C5 firmware image")
        }
    }

    private fun sync() {
        val syncData = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        var failure: Throwable? = null
        repeat(7) {
            runCatching { command(SYNC, syncData, timeoutMs = 500) }
                .onSuccess { return }
                .onFailure { failure = it }
        }
        throw IOException("ESP32-C5 bootloader did not respond; hold BOOT and tap RESET, then try again", failure)
    }

    private fun command(
        opcode: Int,
        data: ByteArray,
        checksum: Int = 0,
        timeoutMs: Int = 3_000,
    ): ByteArray {
        val packet = ByteArrayOutputStream().apply {
            write(0)
            write(opcode)
            write(data.size and 0xff)
            write((data.size ushr 8) and 0xff)
            write(words(checksum))
            write(data)
        }.toByteArray()
        transport.write(slipEncode(packet))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val response = readSlipPacket((deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt())
            if (response.size < 12 || response[0].toInt() != 1 || response[1].toInt() and 0xff != opcode) continue
            val dataLength = response.readUInt16(2)
            if (response.size < 8 + dataLength || dataLength < ROM_STATUS_BYTES) {
                throw IOException("Invalid ESP bootloader response")
            }
            val responseData = response.copyOfRange(8, 8 + dataLength)
            val statusOffset = responseData.size - ROM_STATUS_BYTES
            if (responseData[statusOffset].toInt() != 0) {
                val error = responseData.getOrElse(statusOffset + 1) { 0 }.toInt() and 0xff
                throw IOException("ESP bootloader command 0x${opcode.toString(16)} failed (error 0x${error.toString(16)})")
            }
            return responseData.copyOfRange(0, statusOffset)
        }
        throw IOException("Timed out waiting for ESP bootloader command 0x${opcode.toString(16)}")
    }

    private fun readSlipPacket(timeoutMs: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        var started = false
        var escaped = false
        while (System.currentTimeMillis() < deadline) {
            if (incomingBytes.isEmpty()) {
                val read = transport.read(buffer, minOf(100, (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()))
                for (index in 0 until read) incomingBytes.addLast(buffer[index].toInt() and 0xff)
            }
            if (incomingBytes.isEmpty()) continue
            val byte = incomingBytes.removeFirst()
            if (!started) {
                if (byte == SLIP_END) started = true
                continue
            }
            if (escaped) {
                output.write(if (byte == SLIP_ESC_END) SLIP_END else if (byte == SLIP_ESC_ESC) SLIP_ESC else byte)
                escaped = false
            } else if (byte == SLIP_ESC) {
                escaped = true
            } else if (byte == SLIP_END) {
                if (output.size() > 0) return output.toByteArray()
            } else {
                output.write(byte)
            }
        }
        throw IOException("Timed out reading from ESP32-C5 bootloader")
    }

    companion object {
        const val ESP32_C5_CHIP_ID = 23
        const val FLASH_BLOCK_SIZE = 0x400
        private const val ESP32_C5_BOOTLOADER_OFFSET = 0x2000
        private const val ESP_IMAGE_MAGIC = 0xe9
        private const val FLASH_OFFSET = 0
        private const val ROM_STATUS_BYTES = 4
        private const val FLASH_BEGIN = 0x02
        private const val FLASH_DATA = 0x03
        private const val FLASH_END = 0x04
        private const val SYNC = 0x08
        private const val SPI_ATTACH = 0x0d
        private const val SPI_FLASH_MD5 = 0x13
        private const val GET_SECURITY_INFO = 0x14
        private const val SLIP_END = 0xc0
        private const val SLIP_ESC = 0xdb
        private const val SLIP_ESC_END = 0xdc
        private const val SLIP_ESC_ESC = 0xdd

        internal fun slipEncode(data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(SLIP_END)
            data.forEach { value ->
                when (value.toInt() and 0xff) {
                    SLIP_END -> { write(SLIP_ESC); write(SLIP_ESC_END) }
                    SLIP_ESC -> { write(SLIP_ESC); write(SLIP_ESC_ESC) }
                    else -> write(value.toInt() and 0xff)
                }
            }
            write(SLIP_END)
        }.toByteArray()

        internal fun checksum(data: ByteArray): Int = data.fold(0xef) { result, byte ->
            result xor (byte.toInt() and 0xff)
        }

        private fun eraseTimeout(size: Int): Int = maxOf(10_000, size / 25)
    }
}

private fun words(vararg values: Int): ByteArray = ByteArrayOutputStream().apply {
    values.forEach { value ->
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}.toByteArray()

private fun ByteArray.readUInt16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.readUInt32(offset: Int): Int =
    readUInt16(offset) or (readUInt16(offset + 2) shl 16)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
