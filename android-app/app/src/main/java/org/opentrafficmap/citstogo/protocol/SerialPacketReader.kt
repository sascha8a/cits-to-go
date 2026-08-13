package org.opentrafficmap.citstogo.protocol

import java.io.ByteArrayOutputStream

class SerialPacketReader(
    private val onPacket: (CitsPacket) -> Unit,
    private val onTxResult: (CtgInboundFrame.TxResult) -> Unit,
    private val onProtocolError: (String) -> Unit,
) {
    private val decoder = CtgFrameDecoder()
    private val record = ByteArrayOutputStream(MAX_ENCODED_RECORD)

    fun accept(buffer: ByteArray, count: Int) {
        for (i in 0 until count) {
            val b = buffer[i].toInt() and 0xff
            if (b == 0) {
                finishRecord()
            } else if (record.size() < MAX_ENCODED_RECORD) {
                record.write(b)
            } else {
                record.reset()
                onProtocolError("Serial record exceeded $MAX_ENCODED_RECORD bytes; resynchronizing")
            }
        }
    }

    private fun finishRecord() {
        if (record.size() == 0) return
        val bytes = record.toByteArray()
        record.reset()
        try {
            when (val frame = decoder.decode(bytes)) {
                is CtgInboundFrame.Capture -> onPacket(frame.packet)
                is CtgInboundFrame.TxResult -> onTxResult(frame)
                // Enrollment is handled by BluetoothEnrollment over its dedicated USB
                // control exchange, not by the normal capture/bridge stream. Ignore a
                // stray enrollment acknowledgement here while keeping the sealed
                // frame dispatch exhaustive.
                is CtgInboundFrame.BluetoothEnrollmentResult -> Unit
            }
        } catch (e: Exception) {
            onProtocolError(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_ENCODED_RECORD = 8192
    }
}
