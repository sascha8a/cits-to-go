package org.opentrafficmap.citstogo.intersection

import java.io.Serializable

data class SsemStatus(
    val intersectionKey: IntersectionKey,
    val intersectionSequenceNumber: Int,
    val messageSequenceNumber: Int?,
    val requesterStationId: Long?,
    val requestId: Int?,
    val requestSequenceNumber: Int?,
    val inboundLaneId: Int?,
    val outboundLaneId: Int?,
    val minute: Int?,
    val second: Int?,
    val duration: Int?,
    val responseStatus: SsemResponseStatus,
    val receivedAtMs: Long,
) : Serializable

enum class SsemResponseStatus(val code: Int, val label: String) : Serializable {
    Unknown(0, "Response unknown"),
    Requested(1, "Request received"),
    Processing(2, "Controller processing"),
    WatchOtherTraffic(3, "Watch other traffic"),
    Granted(4, "Request granted"),
    Rejected(5, "Request rejected"),
    MaxPresence(6, "Maximum presence"),
    ReserviceLocked(7, "Reservice locked"),
    Unsupported(-1, "Unsupported response");

    companion object {
        fun fromCode(code: Int): SsemResponseStatus =
            entries.firstOrNull { it.code == code } ?: Unsupported
    }
}

