package org.opentrafficmap.citstogo.bridge

import java.io.Closeable

interface CtgByteTransport : Closeable {
    fun description(): String
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun writeAll(buffer: ByteArray, timeoutMs: Int)
}

enum class ConnectionMode(val wireValue: String, val label: String) {
    USB("usb", "USB"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    ;

    companion object {
        fun fromWireValue(value: String?): ConnectionMode =
            entries.firstOrNull { it.wireValue == value } ?: USB
    }
}
