package org.opentrafficmap.citstogo.bridge

/**
 * Decides whether the bridge may talk to an MQTT broker.
 *
 * MQTT needs both the setting on the settings page and a configured broker URL. Turning it off
 * keeps packet capture, PCAP recording, and CAM transmission working, which is needed for
 * reception tests of nodes without a data connection to a broker.
 */
fun mqttForwardingEnabled(enabledByUser: Boolean, brokerUri: String?): Boolean =
    enabledByUser && !brokerUri.isNullOrBlank()
