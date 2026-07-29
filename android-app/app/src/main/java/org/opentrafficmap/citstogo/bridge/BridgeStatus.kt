package org.opentrafficmap.citstogo.bridge

data class BridgeStatus(
    val running: Boolean = false,
    val usbState: String = "Stopped",
    val mqttState: String = "Disabled",
    val nodeId: String = "",
    val packetTopic: String = "",
    val packets: Long = 0,
    val mqttPublished: Long = 0,
    val mqttQueued: Long = 0,
    val pcapRecording: Boolean = false,
    val pcapPackets: Long = 0,
    val discoveredDevices: Long = 0,
    val truncated: Long = 0,
    val protocolErrors: Long = 0,
    val txRequested: Long = 0,
    val txSuccessful: Long = 0,
    val txFailed: Long = 0,
    val camEnabled: Boolean = false,
    val camSent: Long = 0,
    val lastTxSummary: String = "",
    val lastPacketSummary: String = "",
    val lastError: String = "",
) {
    fun summary(): String {
        val pcap = if (pcapRecording) " | PCAP recording" else ""
        val cam = if (camEnabled) " | CAM active" else ""
        return "$usbState | MQTT $mqttState$pcap$cam"
    }
}
