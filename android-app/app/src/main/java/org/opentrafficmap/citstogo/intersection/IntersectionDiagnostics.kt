package org.opentrafficmap.citstogo.intersection

import java.io.Serializable

data class IntersectionDiagnostics(
    val framesInspected: Long = 0,
    val itsPacketsExtracted: Long = 0,
    val securedGeoNetworkingFrames: Long = 0,
    val securedItsPackets: Long = 0,
    val malformedGeoNetworkingFrames: Long = 0,
    val unsupportedGeoNetworkingFrames: Long = 0,
    val mapemSeen: Long = 0,
    val mapemDecoded: Long = 0,
    val mapemDecodeFailures: Long = 0,
    val spatemSeen: Long = 0,
    val spatemDecoded: Long = 0,
    val spatemDecodeFailures: Long = 0,
    val lastExtractionIssue: String = "",
    val lastDecodeError: String = "",
) : Serializable
