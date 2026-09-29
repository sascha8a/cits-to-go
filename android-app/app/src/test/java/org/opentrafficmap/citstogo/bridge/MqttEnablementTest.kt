package org.opentrafficmap.citstogo.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttEnablementTest {
    @Test
    fun forwardsOnlyWhenTheSettingIsOnAndABrokerIsConfigured() {
        assertTrue(mqttForwardingEnabled(enabledByUser = true, brokerUri = "mqtts://cits1.opentrafficmap.org"))
    }

    @Test
    fun settingOffDisablesForwardingEvenWithABroker() {
        assertFalse(mqttForwardingEnabled(enabledByUser = false, brokerUri = "mqtts://cits1.opentrafficmap.org"))
    }

    @Test
    fun missingBrokerUrlDisablesForwardingEvenWithTheSettingOn() {
        assertFalse(mqttForwardingEnabled(enabledByUser = true, brokerUri = ""))
        assertFalse(mqttForwardingEnabled(enabledByUser = true, brokerUri = "   "))
        assertFalse(mqttForwardingEnabled(enabledByUser = true, brokerUri = null))
    }

    @Test
    fun settingOffWithoutABrokerKeepsForwardingDisabled() {
        assertFalse(mqttForwardingEnabled(enabledByUser = false, brokerUri = ""))
    }
}
