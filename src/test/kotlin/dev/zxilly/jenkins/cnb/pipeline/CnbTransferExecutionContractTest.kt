package dev.zxilly.jenkins.cnb.pipeline

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import hudson.FilePath
import hudson.model.Result
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.steps.BodyInvoker
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CnbTransferExecutionContractTest {
    @TempDir
    lateinit var temporaryDirectory: Path

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

    @Test
    fun `stopping a queued transfer cleans its persisted artifact without starting IO`() {
        val artifact = temporaryDirectory.resolve("queued-transfer.tmp")
        Files.writeString(artifact, "partial")
        val context = RecordingStepContext()
        val queued = AtomicReference<Runnable>()
        val attempts = AtomicInteger()
        val cleanups = AtomicInteger()
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer", Executor(queued::set)) {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String {
                    attempts.incrementAndGet()
                    return "completed"
                }

                override fun afterUnsuccessfulCompletion() {
                    cleanups.incrementAndGet()
                    Files.deleteIfExists(artifact)
                }
            }

        execution.start()
        execution.stop(InterruptedException("cancelled while queued"))
        queued.get().run()

        assertEquals(0, attempts.get())
        assertEquals(1, cleanups.get())
        assertFalse(Files.exists(artifact))
    }

    @Test
    fun `stopping a restored running transfer with no transient task cleans its artifact`() {
        val artifact = temporaryDirectory.resolve("restored-running-transfer.tmp")
        Files.writeString(artifact, "partial")
        val context = RecordingStepContext()
        val queued = AtomicReference<Runnable>()
        val cleanups = AtomicInteger()
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer", Executor(queued::set)) {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "unreachable"

                override fun afterUnsuccessfulCompletion() {
                    cleanups.incrementAndGet()
                    Files.deleteIfExists(artifact)
                }
            }
        execution.start()
        CnbRestartableAsyncStepExecution::class.java
            .getDeclaredField("task")
            .apply { isAccessible = true }
            .set(execution, null)

        execution.stop(InterruptedException("cancelled before resume"))

        assertEquals(1, cleanups.get())
        assertFalse(Files.exists(artifact))
    }

    @Test
    fun `resuming a persisted failed transfer replays its terminal failure`() {
        val context = RecordingStepContext()
        val failure = IOException("transfer failed")
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer") {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = throw failure
            }

        execution.start()
        assertTrue(context.completed.await(10, TimeUnit.SECONDS))
        execution.onResume()

        assertEquals(listOf(failure, failure), context.callbacks)
    }

    @Test
    fun `abort wins while a successful remote transfer is being checkpointed`() {
        val checkpoint = SettableFuture.create<Any>()
        val context = RecordingStepContext(checkpoint)
        val cleaned = CountDownLatch(1)
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer") {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "completed"

                override fun afterUnsuccessfulCompletion() {
                    cleaned.countDown()
                }
            }

        execution.start()
        assertTrue(context.checkpointStarted.await(10, TimeUnit.SECONDS))
        val cancellation = InterruptedException("cancelled during checkpoint")
        execution.stop(cancellation)
        checkpoint.set(Unit)

        assertTrue(cleaned.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(cancellation), context.callbacks)
    }

    @Test
    fun `running cancellation clears interruption for cleanup then restores it`() {
        val artifact = temporaryDirectory.resolve("running-transfer.tmp")
        val context = RecordingStepContext()
        val attemptStarted = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val cleanupSawInterruption = AtomicReference<Boolean>()
        val workerKeptInterruption = AtomicReference<Boolean>()
        val executor =
            Executor { command ->
                Thread {
                    command.run()
                    workerKeptInterruption.set(Thread.currentThread().isInterrupted)
                    workerFinished.countDown()
                }.start()
            }
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer", executor) {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String {
                    Files.writeString(artifact, "partial")
                    attemptStarted.countDown()
                    CountDownLatch(1).await()
                    return "unreachable"
                }

                override fun afterUnsuccessfulCompletion() {
                    cleanupSawInterruption.set(Thread.currentThread().isInterrupted)
                    Files.deleteIfExists(artifact)
                }
            }

        execution.start()
        assertTrue(attemptStarted.await(10, TimeUnit.SECONDS))
        execution.stop(InterruptedException("cancelled while running"))
        assertTrue(workerFinished.await(10, TimeUnit.SECONDS))

        assertEquals(false, cleanupSawInterruption.get())
        assertFalse(Files.exists(artifact))
        assertEquals(true, workerKeptInterruption.get())
    }

    @Test
    fun `stop during checkpoint cleanup also runs unsuccessful cleanup`() {
        val artifact = temporaryDirectory.resolve("checkpoint-cleanup-transfer.tmp")
        Files.writeString(artifact, "persisted")
        val context = RecordingStepContext()
        val checkpointCleanupStarted = CountDownLatch(1)
        val unsuccessfulCleanupFinished = CountDownLatch(1)
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer") {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "completed"

                override fun afterCheckpoint(value: String) {
                    checkpointCleanupStarted.countDown()
                    CountDownLatch(1).await()
                }

                override fun afterUnsuccessfulCompletion() {
                    Files.deleteIfExists(artifact)
                    unsuccessfulCleanupFinished.countDown()
                }
            }

        execution.start()
        assertTrue(checkpointCleanupStarted.await(10, TimeUnit.SECONDS))
        val cancellation = InterruptedException("cancelled during checkpoint cleanup")
        execution.stop(cancellation)

        assertTrue(unsuccessfulCleanupFinished.await(10, TimeUnit.SECONDS))
        assertFalse(Files.exists(artifact))
        assertEquals(listOf(cancellation), context.callbacks)
    }

    @Test
    fun `checkpoint cleanup runs after persistence and before Pipeline success`() {
        val context = RecordingStepContext()
        var cleaned = false
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer") {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "completed"

                override fun afterCheckpoint(value: String) {
                    assertEquals(1, context.checkpoints.get())
                    assertTrue(context.callbacks.isEmpty())
                    cleaned = true
                }
            }

        execution.start()
        assertTrue(context.completed.await(10, TimeUnit.SECONDS))

        assertTrue(cleaned)
        assertEquals(listOf("completed"), context.callbacks)
    }

    @Test
    fun `checkpoint cleanup failure preserves the durable Pipeline success`() {
        val context = RecordingStepContext()
        val unsuccessfulCleanups = AtomicInteger()
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer") {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "completed"

                override fun afterCheckpoint(value: String): Unit = throw IOException("workspace cleanup unavailable")

                override fun afterUnsuccessfulCompletion() {
                    unsuccessfulCleanups.incrementAndGet()
                }
            }

        execution.start()
        assertTrue(context.completed.await(10, TimeUnit.SECONDS))

        assertEquals(1, context.checkpoints.get())
        assertEquals(listOf("completed"), context.callbacks)
        assertEquals(0, unsuccessfulCleanups.get())
        assertTrue(execution.status.contains("completed"))
    }

    @Test
    fun `resuming a persisted stopped transfer replays its cancellation`() {
        val context = RecordingStepContext()
        val queued = AtomicReference<Runnable>()
        val execution =
            object : CnbRestartableAsyncStepExecution<String>(context, "test transfer", Executor(queued::set)) {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "completed"
            }
        val cancellation = InterruptedException("cancelled")

        execution.start()
        execution.stop(cancellation)
        execution.onResume()

        assertEquals(listOf(cancellation, cancellation), context.callbacks)
    }

    @Test
    fun `resume scheduling rejection cleans the persisted transfer artifact`() {
        val artifact = temporaryDirectory.resolve("persisted-transfer.tmp")
        Files.writeString(artifact, "partial")
        val context = RecordingStepContext()
        val execution =
            object :
                CnbRestartableAsyncStepExecution<String>(
                    context,
                    "test transfer",
                    Executor { throw RejectedExecutionException("executor full") },
                ) {
                override fun runAttempt(
                    attempt: Int,
                    resumed: Boolean,
                ): String = "unreachable"

                override fun afterUnsuccessfulCompletion() {
                    Files.deleteIfExists(artifact)
                }
            }

        execution.onResume()

        assertFalse(Files.exists(artifact))
        assertTrue(context.value is IOException)
    }

    @Test
    fun `legacy runner transfer XML without transfer id resumes without null failure`() {
        val workspacePath = temporaryDirectory.resolve("legacy-runner-transfer")
        Files.createDirectories(workspacePath.resolve("logs"))
        val execution =
            CnbDownloadBuildRunnerLogStep("pipeline-1", "logs/runner.log").start(SerializationOnlyStepContext())
        val newTransferId =
            execution.javaClass
                .getDeclaredField("transferId")
                .apply { isAccessible = true }
                .get(execution) as String?
        assertTrue(requireNotNull(newTransferId).matches(Regex("[A-Fa-f0-9-]{36}")))
        val serialized = Jenkins.XSTREAM2.toXML(execution)
        val legacyXml =
            serialized.replace(
                Regex("<transferId(?:\\s[^>]*)?>(?s:.*?)</transferId>|<transferId(?:\\s[^>]*)?/>"),
                "",
            )
        val restored = Jenkins.XSTREAM2.fromXML(legacyXml) as StepExecution
        val restoredContext =
            RecordingStepContext(
                values = mapOf(FilePath::class.java to FilePath(workspacePath.toFile())),
            )
        StepExecution::class.java
            .getDeclaredField("context")
            .apply { isAccessible = true }
            .set(restored, restoredContext)
        CnbRestartableAsyncStepExecution::class.java
            .getDeclaredField("executor")
            .apply { isAccessible = true }
            .set(restored, Executor { throw RejectedExecutionException("executor full") })

        restored.onResume()

        assertTrue(restoredContext.value is IOException)
        val transferId =
            restored.javaClass
                .getDeclaredField("transferId")
                .apply { isAccessible = true }
                .get(restored) as String?
        assertTrue(requireNotNull(transferId).matches(Regex("[A-Fa-f0-9-]{36}")))
    }

    private class SerializationOnlyStepContext : StepContext() {
        override fun <T : Any?> get(key: Class<T>): T = throw UnsupportedOperationException(key.name)

        override fun onSuccess(result: Any?) = Unit

        override fun onFailure(cause: Throwable) = Unit

        override fun isReady(): Boolean = true

        @Suppress("UNCHECKED_CAST")
        override fun saveState(): ListenableFuture<Void> = Futures.immediateVoidFuture() as ListenableFuture<Void>

        override fun setResult(result: Result) = Unit

        override fun newBodyInvoker(): BodyInvoker = throw UnsupportedOperationException()

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private class RecordingStepContext(
        private val checkpoint: ListenableFuture<*>? = null,
        private val values: Map<Class<*>, Any> = emptyMap(),
    ) : StepContext() {
        val completed = CountDownLatch(1)
        val checkpointStarted = CountDownLatch(1)
        val checkpoints = AtomicInteger()
        val callbacks = CopyOnWriteArrayList<Any?>()

        @Volatile
        var value: Any? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> get(key: Class<T>): T = values[key] as? T ?: throw UnsupportedOperationException(key.name)

        override fun onSuccess(result: Any?) {
            value = result
            callbacks += result
            completed.countDown()
        }

        override fun onFailure(cause: Throwable) {
            value = cause
            callbacks += cause
            completed.countDown()
        }

        override fun isReady(): Boolean = true

        override fun saveState(): ListenableFuture<Void> {
            checkpoints.incrementAndGet()
            checkpointStarted.countDown()
            @Suppress("UNCHECKED_CAST")
            checkpoint?.let { return it as ListenableFuture<Void> }
            @Suppress("UNCHECKED_CAST")
            return Futures.immediateVoidFuture() as ListenableFuture<Void>
        }

        override fun setResult(result: Result) = Unit

        override fun newBodyInvoker(): BodyInvoker = throw UnsupportedOperationException()

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
