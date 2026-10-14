package org.opentrafficmap.citstogo.station

/**
 * The ITS message types this app recognises strongly enough to count them per station. The
 * identifiers are the `messageId` values of the ITS PDU header decoded by
 * [org.opentrafficmap.citstogo.intersection.ItsFrameExtractor].
 */
enum class ItsPacketType(val messageId: Int, val label: String) {
    DENM(1, "DENM"),
    CAM(2, "CAM"),
    SPATEM(4, "SPATEM"),
    MAPEM(5, "MAPEM"),
    IVIM(6, "IVIM"),
    SREM(9, "SREM"),
    SSEM(10, "SSEM"),
    ;

    companion object {
        fun fromMessageId(messageId: Int): ItsPacketType? =
            entries.firstOrNull { it.messageId == messageId }
    }
}
