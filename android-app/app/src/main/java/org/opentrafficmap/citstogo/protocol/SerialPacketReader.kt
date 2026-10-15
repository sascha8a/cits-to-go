package org.opentrafficmap.citstogo.protocol

class SerialPacketReader(
    private val onPacket: (CitsPacket) -> Unit,
    private val onTxResult: (CtgInboundFrame.TxResult) -> Unit,
    private val onProtocolError: (String) -> Unit,
    private val onStatistics: (FirmwareStatistics) -> Unit = {},
    diagnosticSource: String = "Serial",
) {
    private val logger = java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Protocol.$diagnosticSource")
    private var receivedBytes = false
    private var decodedFrame = false
    private var receivedStatistics = false
    private var decodeErrors = 0L
    private val decoder = CtgFrameDecoder()
    private val record = ByteArray(MAX_ENCODED_RECORD)
    private var recordLength = 0
    private var discarding = false

    fun accept(buffer: ByteArray, count: Int) {
        require(count in 0..buffer.size)
        if (count > 0 && !receivedBytes) {
            receivedBytes = true
            logger.fine("CTG stream started: first chunk=$count bytes; waiting for COBS record delimiter")
        }
        for (i in 0 until count) {
            val value = buffer[i]
            if (value == 0.toByte()) {
                finishRecord()
                discarding = false
                recordLength = 0
            } else if (!discarding) {
                if (recordLength < record.size) {
                    record[recordLength++] = value
                } else {
                    recordLength = 0
                    discarding = true
                    onProtocolError("Serial record exceeded $MAX_ENCODED_RECORD bytes; resynchronizing")
                }
            }
        }
    }

    private fun finishRecord() {
        if (discarding || recordLength == 0) return
        try {
            val frame = decoder.decode(record, recordLength)
            if (!decodedFrame) {
                decodedFrame = true
                logger.info("First valid CTG frame: type=${frame.javaClass.simpleName} encodedBytes=$recordLength; firmware protocol confirmed")
            }
            when (frame) {
                is CtgInboundFrame.Capture -> onPacket(frame.packet)
                is CtgInboundFrame.TxResult -> onTxResult(frame)
                is CtgInboundFrame.Statistics -> {
                    if (!receivedStatistics) {
                        receivedStatistics = true
                        logger.info("First CTG firmware statistics received: ${frame.statistics}")
                    }
                    onStatistics(frame.statistics)
                }
                // Enrollment is handled by BluetoothEnrollment over its dedicated USB
                // control exchange, not by the normal capture/bridge stream. Ignore a
                // stray enrollment acknowledgement here while keeping the sealed
                // frame dispatch exhaustive.
                is CtgInboundFrame.BluetoothEnrollmentResult -> Unit
            }
        } catch (e: Exception) {
            decodeErrors++
            if (decodeErrors <= 3 || decodeErrors % 100 == 0L) {
                logger.log(java.util.logging.Level.WARNING,
                    "CTG record processing failed: count=$decodeErrors encodedBytes=$recordLength firstValidFrameSeen=$decodedFrame", e)
            }
            onProtocolError(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val MAX_ENCODED_RECORD = 8192
    }
}
