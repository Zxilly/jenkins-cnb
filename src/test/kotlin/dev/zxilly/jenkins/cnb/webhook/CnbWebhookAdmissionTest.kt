package dev.zxilly.jenkins.cnb.webhook

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class CnbWebhookAdmissionTest {
    @Test
    fun `concurrency is rejected without waiting and capacity returns when lease closes`() {
        val admission =
            CnbWebhookAdmission(
                maxConcurrent = 1,
                perServerConcurrent = 1,
                globalRatePerSecond = 10,
                perServerRatePerSecond = 10,
                stripeCount = 1,
            )

        val first = requireNotNull(admission.tryAcquire("cnb-cool"))
        assertNull(admission.tryAcquire("cnb-cool"))
        first.close()
        requireNotNull(admission.tryAcquire("cnb-cool")).close()
    }

    @Test
    fun `token bucket limits sustained requests and refills from monotonic time`() {
        val now = AtomicLong()
        val admission =
            CnbWebhookAdmission(
                maxConcurrent = 2,
                perServerConcurrent = 2,
                globalRatePerSecond = 1,
                perServerRatePerSecond = 1,
                stripeCount = 1,
                nanoTime = now::get,
            )

        repeat(2) { requireNotNull(admission.tryAcquire("cnb-cool")).close() }
        assertNull(admission.tryAcquire("cnb-cool"))
        now.addAndGet(1_000_000_000L)
        requireNotNull(admission.tryAcquire("cnb-cool")).close()
    }
}
