package dev.zxilly.jenkins.cnb.pipeline

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import hudson.model.Result
import org.jenkinsci.plugins.workflow.steps.BodyInvoker
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CnbTransferExecutionContractTest {
    @Test
    fun `workspace transfer executor has bounded workers and backlog`() {
        val executor = newCnbTransferExecutor()
        try {
            assertEquals(4, executor.corePoolSize)
            assertEquals(4, executor.maximumPoolSize)
            assertTrue(executor.queue is ArrayBlockingQueue<*>)
            assertEquals(64, executor.queue.remainingCapacity())
            assertTrue(executor.rejectedExecutionHandler is ThreadPoolExecutor.AbortPolicy)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `workspace transfer steps never block a safe controller restart`() {
        val context = RecordingStepContext()

        val executions =
            listOf(
                CnbUploadReleaseAssetStep("release-1", "dist/plugin.hpi").start(context),
                CnbDownloadReleaseAssetStep("v1.0.0", "plugin.hpi", "downloads/plugin.hpi").start(context),
                CnbDownloadBuildRunnerLogStep("pipeline-1", "logs/runner.log").start(context),
            )

        executions.forEach { assertFalse(it.blocksRestart()) }
    }

    @Test
    fun `successful asynchronous transfer checkpoints before completing Pipeline`() {
        val context = RecordingStepContext()
        val attempts = AtomicInteger()
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer") {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String {
                    attempts.incrementAndGet()
                    return "completed"
                }
            }

        assertFalse(execution.start())
        assertTrue(context.completed.await(10, TimeUnit.SECONDS))

        assertEquals(1, attempts.get())
        assertEquals(1, context.checkpoints.get())
        assertEquals("completed", context.value)
        assertFalse(execution.blocksRestart())
    }

    @Test
    fun `stopping a queued transfer prevents its remote attempt`() {
        val context = RecordingStepContext()
        val queued = AtomicReference<Runnable>()
        val attempts = AtomicInteger()
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer", Executor(queued::set)) {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String {
                    attempts.incrementAndGet()
                    return "completed"
                }
            }

        assertFalse(execution.start())
        execution.stop(InterruptedException("cancelled"))
        queued.get().run()

        assertEquals(0, attempts.get())
        assertTrue(context.value is InterruptedException)
    }

    private class RecordingStepContext : StepContext() {
        val completed = CountDownLatch(1)
        val checkpoints = AtomicInteger()

        @Volatile
        var value: Any? = null

        override fun <T : Any?> get(key: Class<T>): T = throw UnsupportedOperationException(key.name)

        override fun onSuccess(result: Any?) {
            value = result
            completed.countDown()
        }

        override fun onFailure(cause: Throwable) {
            value = cause
            completed.countDown()
        }

        override fun isReady(): Boolean = true

        override fun saveState(): ListenableFuture<Void> {
            checkpoints.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            return Futures.immediateVoidFuture() as ListenableFuture<Void>
        }

        override fun setResult(result: Result) = Unit

        override fun newBodyInvoker(): BodyInvoker = throw UnsupportedOperationException()

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
