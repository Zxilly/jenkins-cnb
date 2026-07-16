package dev.zxilly.jenkins.cnb.webhook

import java.util.concurrent.Semaphore

/** Bounded, allocation-free webhook load shedding keyed by stable server-ID stripes. */
internal class CnbWebhookAdmission(
    maxConcurrent: Int = 32,
    perServerConcurrent: Int = 4,
    globalRatePerSecond: Int = 256,
    perServerRatePerSecond: Int = 64,
    stripeCount: Int = 64,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val global = Semaphore(maxConcurrent, true)
    private val globalRate = TokenBucket(globalRatePerSecond, globalRatePerSecond * 2, nanoTime)
    private val stripes =
        Array(stripeCount) {
            Stripe(
                Semaphore(perServerConcurrent, true),
                TokenBucket(perServerRatePerSecond, perServerRatePerSecond * 2, nanoTime),
            )
        }

    init {
        require(maxConcurrent > 0 && perServerConcurrent > 0) { "Webhook concurrency limits must be positive" }
        require(globalRatePerSecond > 0 && perServerRatePerSecond > 0) { "Webhook rate limits must be positive" }
        require(stripeCount > 0 && stripeCount.countOneBits() == 1) { "Webhook admission stripe count must be a power of two" }
    }

    fun tryAcquire(serverId: String): Lease? {
        if (!global.tryAcquire()) return null
        val stripe = stripes[serverId.hashCode() and (stripes.size - 1)]
        if (!stripe.concurrent.tryAcquire()) {
            global.release()
            return null
        }
        if (!globalRate.tryConsume() || !stripe.rate.tryConsume()) {
            stripe.concurrent.release()
            global.release()
            return null
        }
        return Lease(global, stripe.concurrent)
    }

    internal class Lease(
        private val global: Semaphore,
        private val stripe: Semaphore,
    ) : AutoCloseable {
        private var released = false

        @Synchronized
        override fun close() {
            if (released) return
            released = true
            stripe.release()
            global.release()
        }
    }

    private data class Stripe(
        val concurrent: Semaphore,
        val rate: TokenBucket,
    )

    private class TokenBucket(
        private val refillPerSecond: Int,
        private val capacity: Int,
        private val nanoTime: () -> Long,
    ) {
        private var tokens = capacity.toDouble()
        private var lastRefill = nanoTime()

        @Synchronized
        fun tryConsume(): Boolean {
            val now = nanoTime()
            val elapsed = (now - lastRefill).coerceAtLeast(0)
            if (elapsed > 0) {
                tokens = minOf(capacity.toDouble(), tokens + elapsed.toDouble() * refillPerSecond / NANOS_PER_SECOND)
                lastRefill = now
            }
            if (tokens < 1.0) return false
            tokens -= 1.0
            return true
        }
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
