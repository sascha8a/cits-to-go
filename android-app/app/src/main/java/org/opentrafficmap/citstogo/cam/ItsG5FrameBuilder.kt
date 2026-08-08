package org.opentrafficmap.citstogo.cam

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.opentrafficmap.citstogo.srem.SremIdentity
import org.opentrafficmap.citstogo.srem.SremRequest
import org.opentrafficmap.citstogo.srem.SremUperEncoder

object ItsG5FrameBuilder {
    private const val CAM_PORT = 2001
    private const val SREM_PORT = 2007
    private const val GEONETWORKING_ETHERTYPE = 0x8947

    fun camFrame(
        identity: CamIdentity,
        stationType: StationType,
        position: CamPosition,
        nowUnixMs: Long,
    ): ByteArray {
        val cam = CamUperEncoder.encode(identity, stationType, position, nowUnixMs)
        return shbFrame(
            btpDestinationPort = CAM_PORT,
            payload = cam,
            stationType = stationType,
            macAddress = identity.macAddress,
            positionLatitude = position.latitude,
            positionLongitude = position.longitude,
            speedCms = position.speedCms,
            heading = position.heading,
            positionAccurate = position.positionAccurate,
            nowUnixMs = nowUnixMs,
            qosTid = 3,
        )
    }

    fun sremFrame(
        identity: CamIdentity,
        request: SremRequest,
    ): ByteArray {
        val srem = SremUperEncoder.encode(
            SremIdentity(identity.stationId, identity.macAddress),
            request,
        )
        return gbcSremFrame(identity, request, srem)
    }

    private fun gbcSremFrame(identity: CamIdentity, request: SremRequest, payload: ByteArray): ByteArray {
        val btp = ByteArrayOutputStream().apply {
            putU16(SREM_PORT)
            putU16(0)
            write(payload)
        }.toByteArray()
        val position = request.position
        val geoPositionAvailable = position.latitude in GEONETWORKING_LATITUDE_RANGE &&
            position.longitude in GEONETWORKING_LONGITUDE_RANGE
        val latitude = if (geoPositionAvailable) position.latitude else 0
        val longitude = if (geoPositionAvailable) position.longitude else 0
        val geo = ByteArrayOutputStream().apply {
            // Basic header: version 1, common header, 60 seconds, four remaining hops.
            write(0x11)
            write(0)
            write(0x1a)
            write(4)
            // Common header: BTP-B, GBC, traffic class 2, mobile, maximum four hops.
            write(0x20)
            write(0x40)
            write(0x02)
            write(0x80)
            putU16(btp.size)
            write(4)
            write(0)
            putU16(gbcSequence.getAndIncrement() and 0xffff)
            putU16(0)
            write(gnAddress(request.profile.stationType, identity.macAddress))
            putU32(CamUperEncoder.timestampIts(request.nowUnixMs) and 0xffff_ffffL)
            putI32(latitude)
            putI32(longitude)
            putU16(if (position.positionAccurate && geoPositionAvailable) 0x8000 else 0)
            putU16(position.heading)
            putI32(latitude)
            putI32(longitude)
            putU16(1_000)
            putU16(0)
            putU16(0)
            putU16(0)
            write(btp)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x88.toByte(), 0, 0, 0))
            write(BROADCAST)
            write(identity.macAddress)
            write(BROADCAST)
            val sequenceControl = (wlanSequence.getAndIncrement() and 0x0fff) shl 4
            write(sequenceControl and 0xff)
            write((sequenceControl ushr 8) and 0xff)
            write(0x21) // TID 1, ACK policy 1.
            write(0)
            write(byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 0x03, 0, 0, 0))
            putU16(GEONETWORKING_ETHERTYPE)
            write(geo)
        }.toByteArray()
    }

    private fun shbFrame(
        btpDestinationPort: Int,
        payload: ByteArray,
        stationType: StationType,
        macAddress: ByteArray,
        positionLatitude: Int,
        positionLongitude: Int,
        speedCms: Int,
        heading: Int,
        positionAccurate: Boolean,
        nowUnixMs: Long,
        qosTid: Int,
    ): ByteArray {
        val btp = ByteArrayOutputStream().apply {
            putU16(btpDestinationPort)
            putU16(0)
            write(payload)
        }.toByteArray()
        val geo = ByteArrayOutputStream().apply {
            val geoPositionAvailable =
                positionLatitude in GEONETWORKING_LATITUDE_RANGE &&
                    positionLongitude in GEONETWORKING_LONGITUDE_RANGE
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
            write(gnAddress(stationType, macAddress))
            putU32(CamUperEncoder.timestampIts(nowUnixMs) and 0xffff_ffffL)
            putI32(if (geoPositionAvailable) positionLatitude else 0)
            putI32(if (geoPositionAvailable) positionLongitude else 0)
            val speedAndPai = speedCms.coerceIn(0, 16_383) or
                if (positionAccurate && geoPositionAvailable) 0x8000 else 0
            putU16(speedAndPai)
            putU16(heading.coerceIn(0, 3_600))
            putU32(0) // SHB reserved/DCC-MCO unavailable.
            write(btp)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            // QoS data, broadcast. CAM uses TID 3; SREM captures commonly use TID 1.
            write(byteArrayOf(0x88.toByte(), 0, 0, 0))
            write(BROADCAST)
            write(macAddress)
            write(BROADCAST)
            write(byteArrayOf(0, 0, (qosTid and 0x0f).toByte(), 0))
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
    private val GEONETWORKING_LATITUDE_RANGE = -899_999_999..899_999_999
    private val GEONETWORKING_LONGITUDE_RANGE = -1_799_999_999..1_799_999_999
    private val gbcSequence = AtomicInteger()
    private val wlanSequence = AtomicInteger()
}
