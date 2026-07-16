package dev.zxilly.jenkins.cnb.health

import jenkins.model.Jenkins
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CnbOperationalHealthMonitorTest {
    @Test
    fun `monitor activates for unresolved failures and cannot be dismissed`() {
        val health = CnbOperationalHealth(Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC))
        val monitor = CnbOperationalHealthMonitor(health)

        assertFalse(monitor.isActivated)
        health.recordReporting("production", "team/repository", false, "status update failed")

        assertTrue(monitor.isActivated)
        assertFalse(monitor.isDismissible())
        assertTrue(monitor.isEnabled)
        monitor.disable(true)
        assertTrue(monitor.isEnabled)
        assertTrue(monitor.isActivated)
        @Suppress("DEPRECATION")
        assertEquals(Jenkins.ADMINISTER, monitor.requiredPermission)
    }

    @Test
    fun `newer success resolves the monitor`() {
        val health = CnbOperationalHealth(Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC))
        val monitor = CnbOperationalHealthMonitor(health)

        health.recordWebhook("production", "team/repository", false)
        health.recordWebhook("production", "team/repository", true)

        assertFalse(monitor.isActivated)
    }
}
