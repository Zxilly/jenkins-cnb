package dev.zxilly.jenkins.cnb.webhook

import dev.zxilly.jenkins.cnb.config.CnbServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CnbWebhookProcessorTest {
    @Test
    fun `repository-bound keys prevent one repository from impersonating another`() {
        val dispatched = mutableListOf<CnbWebhookDelivery>()
        val processor = processor(dispatched)
        val body = payload("team/victim", "delivery-1")

        val failure =
            assertThrows(CnbWebhookRequestException::class.java) {
                processor.process("cnb-cool", body, sign(body, ATTACKER_SECRET), "test")
            }

        assertEquals(401, failure.status)
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `accepts the configured repository key and suppresses a replay`() {
        val dispatched = mutableListOf<CnbWebhookDelivery>()
        val processor = processor(dispatched)
        val body = payload("team/victim", "delivery-2")
        val signature = sign(body, VICTIM_SECRET)

        val first = processor.process("cnb-cool", body, signature, "test")
        val replay = processor.process("cnb-cool", body, signature, "test")

        assertFalse(first.duplicate)
        assertTrue(replay.duplicate)
        assertEquals(1, dispatched.size)
        assertEquals(
            "team/victim",
            dispatched
                .single()
                .payload.repository.slug,
        )
    }

    @Test
    fun `returns 503 while the same delivery is in flight and 202 semantics only after completion`() {
        val dispatched = mutableListOf<CnbWebhookDelivery>()
        val body = payload("team/victim", "delivery-in-flight")
        val signature = sign(body, VICTIM_SECRET)
        var nestedStatus = 0
        lateinit var processor: CnbWebhookProcessor
        processor =
            processor(dispatched) { delivery ->
                val inFlight =
                    assertThrows(CnbWebhookRequestException::class.java) {
                        processor.process("cnb-cool", body, signature, "nested")
                    }
                nestedStatus = inFlight.status
                dispatched += delivery
            }

        val accepted = processor.process("cnb-cool", body, signature, "test")
        val completedReplay = processor.process("cnb-cool", body, signature, "test")

        assertEquals(503, nestedStatus)
        assertFalse(accepted.duplicate)
        assertTrue(completedReplay.duplicate)
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `a concurrent retry cannot take over while dispatch exceeds the durable lease`() {
        val dispatched = mutableListOf<CnbWebhookDelivery>()
        val body = payload("team/victim", "delivery-long-dispatch")
        val signature = sign(body, VICTIM_SECRET)
        val clock = MutableClock(NOW)
        val dispatchStarted = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val processor =
            processor(dispatched, clock) { delivery ->
                dispatchStarted.countDown()
                check(allowCompletion.await(5, TimeUnit.SECONDS)) { "test dispatch timed out" }
                dispatched += delivery
            }
        val processing =
            executor.submit<CnbWebhookProcessingResult> {
                processor.process("cnb-cool", body, signature, "first")
            }

        try {
            assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS))
            clock.set(NOW.plusSeconds(61))

            val inFlight =
                assertThrows(CnbWebhookRequestException::class.java) {
                    processor.process("cnb-cool", body, signature, "retry")
                }

            assertEquals(503, inFlight.status)
            allowCompletion.countDown()
            assertFalse(processing.get(5, TimeUnit.SECONDS).duplicate)
            assertTrue(processor.process("cnb-cool", body, signature, "completed-retry").duplicate)
            assertEquals(1, dispatched.size)
        } finally {
            allowCompletion.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed dispatch releases its replay claim for a bridge retry`() {
        val dispatched = mutableListOf<CnbWebhookDelivery>()
        var attempts = 0
        val processor =
            processor(dispatched) { delivery ->
                attempts++
                if (attempts == 1) throw IllegalStateException("simulated dispatch failure")
                dispatched += delivery
            }
        val body = payload("team/victim", "delivery-retry")
        val signature = sign(body, VICTIM_SECRET)

        val failure =
            assertThrows(CnbWebhookRequestException::class.java) {
                processor.process("cnb-cool", body, signature, "test")
            }
        val retry = processor.process("cnb-cool", body, signature, "test")

        assertEquals(500, failure.status)
        assertFalse(retry.duplicate)
        assertEquals(2, attempts)
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `clears every resolved webhook secret after processing`() {
        val server = CnbServer("cnb-cool", "CNB", "https://cnb.cool", "https://api.cnb.cool")
        val current = VICTIM_SECRET.toCharArray()
        val previous = ATTACKER_SECRET.toCharArray()
        val body = payload("team/victim", "delivery-clear-secrets")
        val processor =
            CnbWebhookProcessor(
                serverLookup = CnbWebhookServerLookup { server },
                secretProvider = CnbWebhookSecretProvider { _, _ -> listOf(current, previous) },
                dispatcher = CnbWebhookDispatcher {},
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
            )

        processor.process("cnb-cool", body, sign(body, VICTIM_SECRET), "test")

        assertTrue(current.all { it == '\u0000' })
        assertTrue(previous.all { it == '\u0000' })
    }

    private fun processor(
        dispatched: MutableList<CnbWebhookDelivery>,
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        dispatch: (CnbWebhookDelivery) -> Unit = dispatched::add,
    ): CnbWebhookProcessor {
        val server = CnbServer("cnb-cool", "CNB", "https://cnb.cool", "https://api.cnb.cool")
        val keys = mapOf("team/victim" to VICTIM_SECRET, "team/attacker" to ATTACKER_SECRET)
        return CnbWebhookProcessor(
            serverLookup = CnbWebhookServerLookup { if (it == server.id) server else null },
            secretProvider =
                CnbWebhookSecretProvider { _, repositoryPath ->
                    listOf((keys[repositoryPath] ?: throw NoSuchElementException()).toCharArray())
                },
            dispatcher = CnbWebhookDispatcher(dispatch),
            clock = clock,
        )
    }

    private class MutableClock(
        initial: Instant,
    ) : Clock() {
        private val current = AtomicReference(initial)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock =
            apply {
                require(zone == ZoneOffset.UTC) { "Test clock only supports UTC" }
            }

        override fun instant(): Instant = current.get()

        fun set(value: Instant) {
            current.set(value)
        }
    }

    private fun payload(
        repository: String,
        delivery: String,
    ): ByteArray =
        """
        {
          "CNB_WEB_ENDPOINT":"https://cnb.cool",
          "CNB_API_ENDPOINT":"https://api.cnb.cool",
          "CNB_EVENT":"push",
          "CNB_EVENT_URL":"https://cnb.cool/$repository",
          "CNB_BRANCH":"main",
          "CNB_BRANCH_SHA":"${"a".repeat(40)}",
          "CNB_BEFORE_SHA":"${"b".repeat(40)}",
          "CNB_COMMIT":"${"a".repeat(40)}",
          "CNB_IS_TAG":"false",
          "CNB_REPO_SLUG":"$repository",
          "CNB_REPO_ID":"repo-1",
          "CNB_REPO_URL_HTTPS":"https://cnb.cool/$repository",
          "CNB_BUILD_ID":"build-1",
          "CNB_BUILD_START_TIME":"$NOW",
          "CNB_BUILD_USER":"alice",
          "CNB_BUILD_USER_ID":"user-1",
          "CNB_PIPELINE_ID":"$delivery",
          "CNB_IS_RETRY":"false"
        }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private fun sign(
        body: ByteArray,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-15T10:00:00Z")
        private const val VICTIM_SECRET = "victim-secret-with-more-than-32-bytes"
        private const val ATTACKER_SECRET = "attacker-secret-with-more-than-32-bytes"
    }
}
