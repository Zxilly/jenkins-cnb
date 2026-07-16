package dev.zxilly.jenkins.cnb.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CnbOperationalHealthTest {
    @Test
    fun `records success and failure timestamps with immutable snapshots`() {
        val clock = MutableClock(Instant.parse("2026-07-16T01:00:00Z"))
        val health = CnbOperationalHealth(clock)

        health.recordWebhook("production", "team/repository", false, "delivery rejected")
        clock.advance(Duration.ofMinutes(2))
        health.recordWebhook("production", "team/repository", true, "delivery accepted")

        val snapshot = health.snapshot()
        val entry = snapshot.entries.single()
        assertEquals(CnbHealthComponent.WEBHOOK, entry.component)
        assertEquals(Instant.parse("2026-07-16T01:00:00Z"), entry.lastFailureAt)
        assertEquals(Instant.parse("2026-07-16T01:02:00Z"), entry.lastSuccessAt)
        assertEquals("delivery accepted", entry.summary)
        assertEquals(Instant.parse("2026-07-16T01:02:00Z"), snapshot.generatedAt)
        assertFalse(snapshot.hasRecentUnresolvedFailures())

        @Suppress("UNCHECKED_CAST")
        val mutableView = snapshot.entries as MutableList<CnbOperationalHealthEntry>
        assertThrows(UnsupportedOperationException::class.java) { mutableView.add(entry) }
    }

    @Test
    fun `recent failure is unresolved only while it is newer than success`() {
        val clock = MutableClock(Instant.parse("2026-07-16T02:00:00Z"))
        val health = CnbOperationalHealth(clock)

        health.recordPolling("production", "team/repository", true, "scan completed")
        clock.advance(Duration.ofMinutes(1))
        health.recordPolling("production", "team/repository", false, "scan failed")
        assertTrue(health.snapshot().hasRecentUnresolvedFailures())

        clock.advance(Duration.ofMinutes(1))
        health.recordPolling("production", "team/repository", true, "scan recovered")
        assertFalse(health.snapshot().hasRecentUnresolvedFailures())

        clock.advance(Duration.ofMinutes(1))
        health.recordReporting("production", "team/other", false, "reporting failed")
        assertTrue(health.snapshot().hasRecentUnresolvedFailures())

        clock.advance(Duration.ofHours(25))
        assertFalse(health.snapshot().hasRecentUnresolvedFailures())
        assertTrue(health.snapshot().hasRecentUnresolvedFailures(Duration.ofHours(26)))
    }

    @Test
    fun `capacity uses deterministic access order eviction`() {
        val health = CnbOperationalHealth(Clock.systemUTC(), maxScopes = 3)

        health.recordWebhook("server", "team/a", true)
        health.recordWebhook("server", "team/b", true)
        health.recordWebhook("server", "team/c", true)
        health.recordWebhook("server", "team/a", true, "touch least recently used scope")
        health.recordWebhook("server", "team/d", true)

        assertEquals(
            listOf("team/a", "team/c", "team/d"),
            health.snapshot().entries.map { it.repository },
        )
    }

    @Test
    fun `scope keys and summaries are bounded and redact sensitive material`() {
        val health = CnbOperationalHealth(Clock.systemUTC())
        val opaqueSecret = listOf("A9x7Q2m4", "V8n6L3p5", "R1t0K2j4", "H6").joinToString("")
        val server = "prod\u0000" + "s".repeat(300)
        val repository = "https://cnb.cool/team/repository?access_token=repository-secret#fragment"
        val summary =
            """
            |<script>alert('kept for output escaping')</script>
            |token=tiny-secret password:'password-value' secret = secret-value
            |Authorization: Bearer $opaqueSecret
            |request https://api.cnb.cool/team/repository?token=url-secret&trace=private#debug failed
            |opaque=$opaqueSecret
            |body={"token":"nested-secret","content":"must-not-survive"}
            """.trimMargin() + "x".repeat(1_000)

        health.recordReporting(server, repository, false, summary)

        val entry = health.snapshot().entries.single()
        val stored = listOf(entry.serverId, entry.repository, entry.summary).joinToString(" ")
        assertTrue(entry.serverId.length <= CnbOperationalHealth.MAX_SERVER_ID_LENGTH)
        assertTrue(entry.repository.length <= CnbOperationalHealth.MAX_REPOSITORY_LENGTH)
        assertTrue(entry.summary.length <= CnbOperationalHealth.MAX_SUMMARY_LENGTH)
        assertFalse(stored.any(Char::isISOControl))
        listOf(
            "repository-secret",
            "tiny-secret",
            "password-value",
            "secret-value",
            "url-secret",
            "trace=private",
            opaqueSecret,
            "nested-secret",
            "must-not-survive",
        ).forEach { forbidden -> assertFalse(stored.contains(forbidden), "Leaked: $forbidden") }
        assertFalse(entry.repository.contains('?'))
        assertFalse(entry.summary.contains('?'))
        assertTrue(entry.summary.contains("[REDACTED]"))
    }

    @Test
    fun `blank and hostile scope identifiers receive safe stable values`() {
        val health = CnbOperationalHealth(Clock.systemUTC())

        health.recordWebhook("\u0000\n", " body = private-repository ", false)

        val entry = health.snapshot().entries.single()
        assertEquals(CnbOperationalHealth.UNKNOWN_SCOPE, entry.serverId)
        assertEquals("body = [REDACTED]", entry.repository)
        assertNotNull(entry.summary)
    }

    @Test
    fun `concurrent writers remain bounded and publish complete snapshots`() {
        val health = CnbOperationalHealth(Clock.systemUTC())
        val workers = 12
        val writes = 2_000
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                (0 until writes).map { index ->
                    executor.submit {
                        start.await()
                        when (index % 3) {
                            0 -> health.recordWebhook("server-${index % 4}", "team/repo-$index", index % 2 == 0)
                            1 -> health.recordPolling("server-${index % 4}", "team/repo-$index", index % 2 == 0)
                            else -> health.recordReporting("server-${index % 4}", "team/repo-$index", index % 2 == 0)
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val snapshot = health.snapshot()
        assertEquals(CnbOperationalHealth.DEFAULT_MAX_SCOPES, snapshot.entries.size)
        assertEquals(
            snapshot.entries.size,
            snapshot.entries
                .map { Triple(it.serverId, it.repository, it.component) }
                .toSet()
                .size,
        )
        assertTrue(snapshot.entries.all { it.lastSuccessAt != null || it.lastFailureAt != null })
    }

    @Test
    fun `configured capacity cannot exceed production safety boundary`() {
        assertThrows(IllegalArgumentException::class.java) { CnbOperationalHealth(Clock.systemUTC(), 0) }
        assertThrows(IllegalArgumentException::class.java) {
            CnbOperationalHealth(Clock.systemUTC(), CnbOperationalHealth.DEFAULT_MAX_SCOPES + 1)
        }
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
