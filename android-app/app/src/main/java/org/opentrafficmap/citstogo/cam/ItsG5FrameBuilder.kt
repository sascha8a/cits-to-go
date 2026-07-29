package org.opentrafficmap.citstogo.cam

import java.io.ByteArrayOutputStream

object ItsG5FrameBuilder {
    private const val CAM_PORT = 2001
    private const val GEONETWORKING_ETHERTYPE = 0x8947

    fun camFrame(
        identity: CamIdentity,
        stationType: StationType,
        position: CamPosition,
        nowUnixMs: Long,
    ): ByteArray {
        val cam = CamUperEncoder.encode(identity, stationType, position, nowUnixMs)
        val btp = ByteArrayOutputStream().apply {
            putU16(CAM_PORT)
            putU16(0)
            write(cam)
        }.toByteArray()
        val geo = ByteArrayOutputStream().apply {
            // Basic: version 1, next-header common, lifetime 1 s, one hop.
            write(0x11)
            write(0)
            write(0x05)
            write(1)
            // Common: BTP-B, SHB, traffic class 2, mobile flag.
            write(0x20)
            write(0x50)
            write(0x02)
            write(if (stationType == StationType.ROAD_SIDE_UNIT) 0 else 0x80)
            putU16(btp.size)
            write(1)
            write(0)
            write(gnAddress(stationType, identity.macAddress))
            putU32(CamUperEncoder.timestampIts(nowUnixMs) and 0xffff_ffffL)
            putI32(position.latitude)
            putI32(position.longitude)
            val speedAndPai = position.speedCms.coerceIn(0, 16_383) or
                if (position.positionAccurate) 0x8000 else 0
            putU16(speedAndPai)
            putU16(position.heading.coerceIn(0, 3_600))
            putU32(0) // SHB reserved/DCC-MCO unavailable.
            write(btp)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            // QoS data, broadcast, TID 3 (CAM maps to user priority 3 / AC_BE).
            write(byteArrayOf(0x88.toByte(), 0, 0, 0))
            write(BROADCAST)
            write(identity.macAddress)
            write(BROADCAST)
            write(byteArrayOf(0, 0, 3, 0))
            // LLC/SNAP with GeoNetworking EtherType.
            write(byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 0x03, 0, 0, 0))
            putU16(GEONETWORKING_ETHERTYPE)
            write(geo)
        }.toByteArray()
    }

    private fun gnAddress(stationType: StationType, mac: ByteArray): ByteArray =
        byteArrayOf(((stationType.code and 0x1f) shl 2).toByte(), 0) + mac

    private fun ByteArrayOutputStream.putU16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.putU32(value: Long) {
        write(((value ushr 24) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write((value and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.putI32(value: Int) = putU32(value.toLong() and 0xffff_ffffL)

    private val BROADCAST = ByteArray(6) { 0xff.toByte() }
}
