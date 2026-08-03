package org.opentrafficmap.citstogo.srem

data class SremIdentity(
    val stationId: Long,
    val macAddress: ByteArray,
) {
    init {
        require(stationId in 0..0xffff_ffffL)
        require(macAddress.size == 6)
    }
}

data class SremRequest(
    val region: Int?,
    val intersectionId: Int,
    val requestId: Int,
    val sequenceNumber: Int,
    val inboundLaneId: Int,
    val outboundLaneId: Int,
    val position: SremPosition,
    val nowUnixMs: Long,
)

data class SremPosition(
    val latitude: Int,
    val longitude: Int,
)

