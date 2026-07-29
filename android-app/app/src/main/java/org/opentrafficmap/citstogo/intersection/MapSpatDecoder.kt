package org.opentrafficmap.citstogo.intersection

object MapSpatDecoder {
    const val MESSAGE_ID_SPATEM = 4
    const val MESSAGE_ID_MAPEM = 5
    const val BTP_PORT_MAPEM = 2003
    const val BTP_PORT_SPATEM = 2004

    fun decodeMap(packet: ItsPacket, receivedAtMs: Long): List<MapIntersection> {
        if (packet.protocolVersion != 2 || packet.messageId != MESSAGE_ID_MAPEM) return emptyList()
        val reader = UperBitReader(packet.payload, packet.bodyOffset)
        return readMapData(reader, receivedAtMs)
    }

    fun decodeSpat(packet: ItsPacket, receivedAtMs: Long): List<SpatIntersection> {
        if (packet.protocolVersion != 2 || packet.messageId != MESSAGE_ID_SPATEM) return emptyList()
        val reader = UperBitReader(packet.payload, packet.bodyOffset)
        return readSpat(reader, receivedAtMs)
    }

    private fun readMapData(reader: UperBitReader, receivedAtMs: Long): List<MapIntersection> {
        val hasExtension = reader.bit()
        if (hasExtension) throw IntersectionDecodeException("MapData extensions are not supported")
        val hasTimeStamp = reader.bit()
        val hasLayerType = reader.bit()
        val hasLayerId = reader.bit()
        val hasIntersections = reader.bit()
        val hasRoadSegments = reader.bit()
        val hasDataParameters = reader.bit()
        val hasRestrictionList = reader.bit()
        val hasRegional = reader.bit()
        if (hasTimeStamp) reader.constrained(0, 527040)
        reader.constrained(0, 127)
        if (hasLayerType) readExtensibleEnum(reader, 8)
        if (hasLayerId) reader.constrained(0, 100)
        val intersections = if (hasIntersections) {
            List(reader.sequenceLength(1, 32)) { readIntersectionGeometry(reader, receivedAtMs) }
        } else {
            emptyList()
        }
        if (hasRoadSegments || hasDataParameters || hasRestrictionList || hasRegional) {
            throw IntersectionDecodeException("Unsupported MAPEM optional branch")
        }
        return intersections
    }

    private fun readIntersectionGeometry(reader: UperBitReader, receivedAtMs: Long): MapIntersection {
        val hasExtension = reader.bit()
        if (hasExtension) throw IntersectionDecodeException("IntersectionGeometry extensions are not supported")
        val hasName = reader.bit()
        val hasLaneWidth = reader.bit()
        val hasSpeedLimits = reader.bit()
        val hasPreemptPriority = reader.bit()
        val hasRegional = reader.bit()
        val name = if (hasName) reader.ia5String(1, 63) else null
        val key = reader.intersectionReferenceId()
        val revision = reader.constrained(0, 127).toInt()
        val refPoint = reader.position3D()
        val laneWidth = if (hasLaneWidth) reader.constrained(0, 32767).toInt() else null
        if (hasSpeedLimits) repeat(reader.sequenceLength(1, 9)) { reader.regulatorySpeedLimit() }
        val lanes = List(reader.sequenceLength(1, 255)) { reader.genericLane() }
        if (hasPreemptPriority || hasRegional) {
            throw IntersectionDecodeException("Unsupported IntersectionGeometry optional branch")
        }
        return MapIntersection(
            key = key,
            name = name,
            revision = revision,
            latitude = refPoint.first,
            longitude = refPoint.second,
            laneWidthCm = laneWidth,
            lanes = lanes,
            receivedAtMs = receivedAtMs,
        )
    }

    private fun readSpat(reader: UperBitReader, receivedAtMs: Long): List<SpatIntersection> {
        val hasExtension = reader.bit()
        if (hasExtension) throw IntersectionDecodeException("SPAT extensions are not supported")
        val hasTimeStamp = reader.bit()
        val hasName = reader.bit()
        val hasRegional = reader.bit()
        if (hasTimeStamp) reader.constrained(0, 527040)
        if (hasName) reader.ia5String(1, 63)
        val intersections = List(reader.sequenceLength(1, 32)) { reader.intersectionState(receivedAtMs) }
        if (hasRegional) throw IntersectionDecodeException("SPAT regional extensions are not supported")
        return intersections
    }

    private fun UperBitReader.intersectionState(receivedAtMs: Long): SpatIntersection {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("IntersectionState extensions are not supported")
        val hasName = bit()
        val hasMoy = bit()
        val hasTimestamp = bit()
        val hasEnabledLanes = bit()
        val hasManeuverAssist = bit()
        val hasRegional = bit()
        if (hasName) ia5String(1, 63)
        val key = intersectionReferenceId()
        val revision = constrained(0, 127).toInt()
        skipBits(16)
        val moy = if (hasMoy) constrained(0, 527040).toInt() else null
        val timestamp = if (hasTimestamp) constrained(0, 65535).toInt() else null
        if (hasEnabledLanes) repeat(sequenceLength(1, 16)) { constrained(0, 255) }
        val movements = List(sequenceLength(1, 255)) { movementState() }
        if (hasManeuverAssist) repeat(sequenceLength(1, 16)) { connectionManeuverAssist() }
        if (hasRegional) throw IntersectionDecodeException("IntersectionState regional extensions are not supported")
        return SpatIntersection(key, revision, moy, timestamp, movements, receivedAtMs)
    }

    private fun UperBitReader.movementState(): SignalMovement {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("MovementState extensions are not supported")
        val hasName = bit()
        val hasManeuverAssist = bit()
        val hasRegional = bit()
        if (hasName) ia5String(1, 63)
        val signalGroup = constrained(0, 255).toInt()
        val events = List(sequenceLength(1, 16)) { movementEvent() }
        val connectionIds = if (hasManeuverAssist) {
            List(sequenceLength(1, 16)) { connectionManeuverAssist() }
        } else {
            emptyList()
        }
        if (hasRegional) throw IntersectionDecodeException("MovementState regional extensions are not supported")
        return SignalMovement(signalGroup, events, connectionIds)
    }

    private fun UperBitReader.movementEvent(): SignalEvent {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("MovementEvent extensions are not supported")
        val hasTiming = bit()
        val hasSpeeds = bit()
        val hasRegional = bit()
        val state = MovementPhaseState.fromCode(readRootEnum(this, 10))
        val timing = if (hasTiming) timeChangeDetails() else null
        if (hasSpeeds) throw IntersectionDecodeException("MovementEvent advisory speeds are not supported")
        if (hasRegional) throw IntersectionDecodeException("MovementEvent regional extensions are not supported")
        return SignalEvent(
            state = state,
            minEndTime = timing?.minEndTime,
            likelyTime = timing?.likelyTime,
            maxEndTime = timing?.maxEndTime,
            confidence = timing?.confidence,
        )
    }

    private data class Timing(
        val minEndTime: Int,
        val likelyTime: Int?,
        val maxEndTime: Int?,
        val confidence: Int?,
    )

    private fun UperBitReader.timeChangeDetails(): Timing {
        val hasStartTime = bit()
        val hasMaxEndTime = bit()
        val hasLikelyTime = bit()
        val hasConfidence = bit()
        val hasNextTime = bit()
        if (hasStartTime) constrained(0, 36001)
        val minEndTime = constrained(0, 36001).toInt()
        val maxEndTime = if (hasMaxEndTime) constrained(0, 36001).toInt() else null
        val likelyTime = if (hasLikelyTime) constrained(0, 36001).toInt() else null
        val confidence = if (hasConfidence) constrained(0, 15).toInt() else null
        if (hasNextTime) constrained(0, 36001)
        return Timing(minEndTime, likelyTime, maxEndTime, confidence)
    }

    private fun UperBitReader.genericLane(): MapLane {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("GenericLane extensions are not supported")
        val hasName = bit()
        val hasIngress = bit()
        val hasEgress = bit()
        val hasManeuvers = bit()
        val hasConnectsTo = bit()
        val hasOverlays = bit()
        val hasRegional = bit()
        val laneId = constrained(0, 255).toInt()
        if (hasName) ia5String(1, 63)
        val ingressApproach = if (hasIngress) constrained(0, 15).toInt() else null
        val egressApproach = if (hasEgress) constrained(0, 15).toInt() else null
        val attrs = laneAttributes()
        if (hasManeuvers) skipBits(12)
        val nodes = nodeList()
        val connectsTo = if (hasConnectsTo) {
            List(sequenceLength(1, 16)) { connection() }
        } else {
            emptyList()
        }
        if (hasOverlays) repeat(sequenceLength(1, 5)) { constrained(0, 255) }
        if (hasRegional) throw IntersectionDecodeException("GenericLane regional extensions are not supported")
        return MapLane(
            id = laneId,
            ingressApproach = ingressApproach,
            egressApproach = egressApproach,
            laneType = attrs.type,
            ingress = attrs.ingress,
            egress = attrs.egress,
            nodes = nodes,
            connections = connectsTo,
        )
    }

    private data class LaneAttrs(val type: LaneType, val ingress: Boolean, val egress: Boolean)

    private fun UperBitReader.laneAttributes(): LaneAttrs {
        val hasRegional = bit()
        val ingress = bit()
        val egress = bit()
        skipBits(10)
        val typeIndex = readChoiceIndex(rootChoices = 8, extensible = true)
        when (typeIndex) {
            0 -> readPossiblyExtensibleBitString(8)
            1, 4, 6, 7 -> skipBits(16)
            2 -> skipBits(16)
            3 -> skipBits(16)
            5 -> skipBits(16)
            else -> throw IntersectionDecodeException("Unsupported LaneTypeAttributes extension")
        }
        if (hasRegional) throw IntersectionDecodeException("LaneAttributes regional extension is not supported")
        return LaneAttrs(
            type = when (typeIndex) {
                0 -> LaneType.Vehicle
                1 -> LaneType.Crosswalk
                2 -> LaneType.Bike
                3 -> LaneType.Sidewalk
                4 -> LaneType.Median
                5 -> LaneType.Striping
                6 -> LaneType.TrackedVehicle
                7 -> LaneType.Parking
                else -> LaneType.Other
            },
            ingress = ingress,
            egress = egress,
        )
    }

    private fun UperBitReader.nodeList(): List<LaneNode> {
        val choice = readChoiceIndex(rootChoices = 2, extensible = true)
        if (choice != 0) throw IntersectionDecodeException("Computed lanes are not supported")
        var x = 0
        var y = 0
        return List(sequenceLength(2, 63)) {
            val node = nodeXY()
            x += node.xCm
            y += node.yCm
            node.copy(xCm = x, yCm = y)
        }
    }

    private fun UperBitReader.nodeXY(): LaneNode {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("NodeXY extensions are not supported")
        val hasAttributes = bit()
        val delta = nodeOffsetPoint()
        val attrs = if (hasAttributes) nodeAttributeSetXY() else NodeAttrs()
        return LaneNode(delta.first, delta.second, attrs.stopLine, attrs.widthDelta)
    }

    private data class NodeAttrs(val stopLine: Boolean = false, val widthDelta: Int? = null)

    private fun UperBitReader.nodeAttributeSetXY(): NodeAttrs {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("NodeAttributeSetXY extensions are not supported")
        val hasLocal = bit()
        val hasDisabled = bit()
        val hasEnabled = bit()
        val hasData = bit()
        val hasWidth = bit()
        val hasElevation = bit()
        val hasRegional = bit()
        var stopLine = false
        if (hasLocal) {
            repeat(sequenceLength(1, 8)) {
                if (readExtensibleEnum(this, 12) == 1) stopLine = true
            }
        }
        if (hasDisabled) repeat(sequenceLength(1, 8)) { readExtensibleEnum(this, 40) }
        if (hasEnabled) repeat(sequenceLength(1, 8)) { readExtensibleEnum(this, 40) }
        if (hasData) repeat(sequenceLength(1, 8)) { laneDataAttribute() }
        val width = if (hasWidth) signedConstrained(-512, 511) else null
        if (hasElevation) signedConstrained(-512, 511)
        if (hasRegional) throw IntersectionDecodeException("NodeAttributeSetXY regional extension is not supported")
        return NodeAttrs(stopLine, width)
    }

    private fun UperBitReader.laneDataAttribute() {
        when (readChoiceIndex(rootChoices = 6, extensible = true)) {
            0 -> signedConstrained(-150, 150)
            1, 2, 3 -> signedConstrained(-128, 127)
            4 -> signedConstrained(-180, 180)
            5 -> repeat(sequenceLength(1, 9)) { regulatorySpeedLimit() }
            else -> throw IntersectionDecodeException("Unsupported LaneDataAttribute extension")
        }
    }

    private fun UperBitReader.nodeOffsetPoint(): Pair<Int, Int> {
        return when (readChoiceIndex(rootChoices = 8, extensible = false)) {
            0 -> signedConstrained(-512, 511) to signedConstrained(-512, 511)
            1 -> signedConstrained(-1024, 1023) to signedConstrained(-1024, 1023)
            2 -> signedConstrained(-2048, 2047) to signedConstrained(-2048, 2047)
            3 -> signedConstrained(-4096, 4095) to signedConstrained(-4096, 4095)
            4 -> signedConstrained(-8192, 8191) to signedConstrained(-8192, 8191)
            5 -> signedConstrained(-32768, 32767) to signedConstrained(-32768, 32767)
            6 -> {
                constrained(-1800000000, 1800000001)
                constrained(-900000000, 900000001)
                0 to 0
            }
            else -> throw IntersectionDecodeException("Regional node offsets are not supported")
        }
    }

    private fun UperBitReader.connection(): LaneConnection {
        val hasRemote = bit()
        val hasSignalGroup = bit()
        val hasUserClass = bit()
        val hasConnectionId = bit()
        val hasManeuver = bit()
        val lane = constrained(0, 255).toInt()
        if (hasManeuver) skipBits(12)
        if (hasRemote) intersectionReferenceId()
        val signalGroup = if (hasSignalGroup) constrained(0, 255).toInt() else null
        if (hasUserClass) constrained(0, 255)
        val connectionId = if (hasConnectionId) constrained(0, 255).toInt() else null
        return LaneConnection(lane, signalGroup, connectionId)
    }

    private fun UperBitReader.connectionManeuverAssist(): Int {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("ConnectionManeuverAssist extensions are not supported")
        val hasQueueLength = bit()
        val hasAvailableStorageLength = bit()
        val hasWaitOnStop = bit()
        val hasPedBicycleDetect = bit()
        val hasRegional = bit()
        val connectionId = constrained(0, 255).toInt()
        if (hasQueueLength) constrained(0, 10000)
        if (hasAvailableStorageLength) constrained(0, 10000)
        if (hasWaitOnStop) bit()
        if (hasPedBicycleDetect) bit()
        if (hasRegional) throw IntersectionDecodeException("ConnectionManeuverAssist regional extensions are not supported")
        return connectionId
    }

    private fun UperBitReader.intersectionReferenceId(): IntersectionKey {
        val hasRegion = bit()
        val region = if (hasRegion) constrained(0, 65535).toInt() else null
        val id = constrained(0, 65535).toInt()
        return IntersectionKey(region, id)
    }

    private fun UperBitReader.position3D(): Pair<Int, Int> {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("Position3D extensions are not supported")
        val hasElevation = bit()
        val hasRegional = bit()
        val lat = constrained(-900000000, 900000001).toInt()
        val lon = constrained(-1800000000, 1800000001).toInt()
        if (hasElevation) constrained(-4096, 61439)
        if (hasRegional) throw IntersectionDecodeException("Position3D regional extensions are not supported")
        return lat to lon
    }

    private fun UperBitReader.regulatorySpeedLimit() {
        readExtensibleEnum(this, 13)
        constrained(0, 8191)
    }

    private fun UperBitReader.ia5String(minimum: Int, maximum: Int): String {
        val length = sequenceLength(minimum, maximum)
        val chars = CharArray(length) { bits(7).toInt().toChar() }
        return chars.concatToString()
    }

    private fun UperBitReader.readPossiblyExtensibleBitString(rootSize: Int) {
        val extended = bit()
        if (extended) throw IntersectionDecodeException("Extensible BIT STRING outside root size is not supported")
        skipBits(rootSize)
    }

    private fun UperBitReader.readChoiceIndex(rootChoices: Int, extensible: Boolean): Int {
        if (extensible && bit()) {
            throw IntersectionDecodeException("CHOICE extension is not supported")
        }
        val width = 32 - Integer.numberOfLeadingZeros(rootChoices - 1)
        return bits(width).toInt()
    }

    private fun readExtensibleEnum(reader: UperBitReader, rootValues: Int): Int {
        if (reader.bit()) throw IntersectionDecodeException("ENUMERATED extension is not supported")
        return readRootEnum(reader, rootValues)
    }

    private fun readRootEnum(reader: UperBitReader, rootValues: Int): Int {
        val width = 32 - Integer.numberOfLeadingZeros(rootValues - 1)
        return reader.bits(width).toInt()
    }
}
