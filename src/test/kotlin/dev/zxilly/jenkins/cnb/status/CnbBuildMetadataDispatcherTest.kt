package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.health.CnbHealthComponent
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import hudson.model.Item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CnbBuildMetadataDispatcherTest {
    @Test
    fun `reporting retries record every failure and a later success resolves health`() {
        val action = pendingAction("health-retry")
        val dispatchClock = FakeClock()
        val healthClock = MutableHealthClock(Instant.parse("2026-07-16T00:00:00Z"))
        val health = CnbOperationalHealth(healthClock)
        val workers = SwitchableExecutor()
        val wakeups = RecordingWakeups(dispatchClock)
        val reports = AtomicInteger()
        val reporter =
            CnbOperationalHealthMetadataReporter(
                delegate =
                    CnbMetadataReporter { _, _ ->
                        if (reports.getAndIncrement() < 2) {
                            throw CnbApiException(
                                "Bearer reporting-secret-must-not-leak",
                                retryable = true,
                            )
                        }
                        CnbBuildMetadataReportResult("comment-health")
                    },
                health = health,
            )
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = wakeups,
                reporter = reporter,
                clockMillis = dispatchClock::now,
            )

        assertTrue(runtime.scheduleItem(null, action))
        workers.runNext()
        val firstFailure = health.snapshot().entries.single()

        assertEquals(CnbHealthComponent.REPORTING, firstFailure.component)
        assertEquals("cnb-cool", firstFailure.serverId)
        assertEquals("team/project", firstFailure.repository)
        assertEquals("state=running status=failure class=Cnb.Api.Exception", firstFailure.summary)
        assertNotNull(firstFailure.lastFailureAt)
        assertNull(firstFailure.lastSuccessAt)

        healthClock.advance(Duration.ofSeconds(1))
        dispatchClock.advance(wakeups.singleActive().delayMillis)
        wakeups.runDue()
        workers.runNext()
        val secondFailure = health.snapshot().entries.single()

        assertTrue(requireNotNull(secondFailure.lastFailureAt).isAfter(requireNotNull(firstFailure.lastFailureAt)))
        assertEquals("state=running status=failure class=Cnb.Api.Exception", secondFailure.summary)

        healthClock.advance(Duration.ofSeconds(1))
        dispatchClock.advance(wakeups.singleActive().delayMillis)
        wakeups.runDue()
        workers.runNext()
        val recovered = health.snapshot()
        val recoveredEntry = recovered.entries.single()

        assertEquals(3, reports.get())
        assertFalse(action.isPending())
        assertEquals("state=running status=success", recoveredEntry.summary)
        assertTrue(requireNotNull(recoveredEntry.lastSuccessAt).isAfter(requireNotNull(recoveredEntry.lastFailureAt)))
        assertFalse(recovered.hasRecentUnresolvedFailures(Duration.ofHours(1)))
        assertTrue("reporting-secret" !in recoveredEntry.summary)
        runtime.shutdown()
    }

    @Test
    fun `unexpected reporting failure remains pending and recovers after configuration repair`() {
        val action = pendingAction("configuration-repair")
        val clock = FakeClock()
        val wakeups = RecordingWakeups(clock)
        val workers = SwitchableExecutor()
        val reports = AtomicInteger()
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = wakeups,
                reporter =
                    CnbMetadataReporter { _, _ ->
                        if (reports.getAndIncrement() == 0) throw IllegalArgumentException("credential unavailable")
                        CnbBuildMetadataReportResult("comment-recovered")
                    },
                clockMillis = clock::now,
            )

        assertTrue(runtime.scheduleItem(null, action))
        workers.runNext()

        assertTrue(action.isPending())
        assertEquals(1, runtime.retryAttempt(action))

        clock.advance(wakeups.singleActive().delayMillis)
        wakeups.runDue()
        workers.runNext()

        assertFalse(action.isPending())
        assertEquals(2, reports.get())
        runtime.shutdown()
    }

    @Test
    fun `capacity is bounded and all rejected keys share one drain wakeup`() {
        val clock = FakeClock()
        val wakeups = RecordingWakeups(clock)
        val recoveryRequests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = SwitchableExecutor(reject = true),
                wakeups = wakeups,
                reporter = CnbMetadataReporter { _, _ -> error("reporter must not run") },
                pendingCapacity = 2,
                clockMillis = clock::now,
                recoveryRequests = recoveryRequests,
            )
        val first = pendingAction("first")
        val second = pendingAction("second")
        val overflow = pendingAction("overflow")

        assertTrue(runtime.scheduleItem(null, first))
        assertTrue(runtime.scheduleItem(null, second))
        assertFalse(runtime.scheduleItem(null, overflow))
        // Coalescing the same durable Action never consumes another capacity or wakeup slot.
        assertTrue(runtime.scheduleItem(null, first))

        assertEquals(2, runtime.pendingCount())
        assertEquals(0, runtime.activeCount())
        assertEquals(1, wakeups.activeCount())
        assertEquals(1, wakeups.scheduleCalls)
        assertEquals(CnbBuildMetadataDispatchRuntime.BACKPRESSURE_RETRY_DELAY_MILLIS, wakeups.singleActive().delayMillis)
        assertTrue(first.isPending())
        assertTrue(second.isPending())
        assertTrue(overflow.isPending())
        assertNull(runtime.retryAttempt(overflow))
        assertNotNull(recoveryRequests.pendingGeneration())
        runtime.shutdown()
    }

    @Test
    fun `worker rejection recovers and overflow remains available to periodic reconciliation`() {
        val clock = FakeClock()
        val wakeups = RecordingWakeups(clock)
        val workers = SwitchableExecutor(reject = true)
        val reports = AtomicInteger()
        val recoveryRequests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = wakeups,
                reporter =
                    CnbMetadataReporter { _, _ ->
                        reports.incrementAndGet()
                        CnbBuildMetadataReportResult(null)
                    },
                pendingCapacity = 1,
                clockMillis = clock::now,
                recoveryRequests = recoveryRequests,
            )
        val admitted = pendingAction("admitted")
        val overflow = pendingAction("overflow-recovery")

        assertTrue(runtime.scheduleItem(null, admitted))
        assertFalse(runtime.scheduleItem(null, overflow))
        assertTrue(overflow.isPending(), "overflow must not mark or mutate the durable Action")

        workers.reject = false
        clock.advance(CnbBuildMetadataDispatchRuntime.BACKPRESSURE_RETRY_DELAY_MILLIS)
        wakeups.runDue()
        workers.runNext()
        assertFalse(admitted.isPending())

        // This is the same call made by CnbBuildMetadataReconciliationWork when it rediscovers an
        // overflowed durable Action.
        assertTrue(runtime.scheduleItem(null, overflow))
        workers.runNext()

        assertFalse(overflow.isPending())
        assertEquals(2, reports.get())
        assertEquals(0, runtime.pendingCount())
        assertEquals(0, runtime.activeCount())
        runtime.shutdown()
    }

    @Test
    fun `worker rejection defers 1024 eligible keys behind one asynchronous wakeup`() {
        val clock = FakeClock()
        val wakeups = RecordingWakeups(clock)
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = SwitchableExecutor(reject = true),
                wakeups = wakeups,
                reporter = CnbMetadataReporter { _, _ -> error("reporter must not run") },
                pendingCapacity = 1_024,
                clockMillis = clock::now,
            )

        repeat(1_024) { index ->
            assertTrue(runtime.scheduleItem(null, pendingAction("saturated-$index"), delaySeconds = 1))
        }
        assertEquals(1_024, runtime.pendingCount())
        assertEquals(1, wakeups.activeCount())

        clock.advance(1_000)
        wakeups.runDue()

        assertEquals(1_024, runtime.pendingCount())
        assertEquals(0, runtime.activeCount())
        assertEquals(1, wakeups.activeCount())
        assertEquals(2, wakeups.scheduleCalls)
        assertEquals(CnbBuildMetadataDispatchRuntime.BACKPRESSURE_RETRY_DELAY_MILLIS, wakeups.singleActive().delayMillis)
        runtime.shutdown()
    }

    @Test
    fun `newer coalesced work retains the existing item credential context`() {
        val clock = FakeClock()
        val workers = SwitchableExecutor()
        val wakeups = RecordingWakeups(clock)
        val observed = arrayOfNulls<Item>(1)
        val item =
            Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { proxy, method, arguments ->
                when (method.name) {
                    "toString" -> "folder/job"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.singleOrNull()
                    else -> null
                }
            } as Item
        val action = pendingAction("coalesced-context")
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = wakeups,
                reporter =
                    CnbMetadataReporter { _, context ->
                        observed[0] = context
                        CnbBuildMetadataReportResult(null)
                    },
                clockMillis = clock::now,
            )

        assertTrue(runtime.scheduleItem(item, action, delaySeconds = 1))
        action.advance(
            action.target(),
            CnbBuildMetadataState.SUCCESS,
            "folder/job #coalesced-context",
            "job/folder/job/coalesced-context/",
        )
        assertTrue(runtime.scheduleItem(null, action, delaySeconds = 1))

        clock.advance(1_000)
        wakeups.runDue()
        workers.runNext()

        assertSame(item, observed[0])
        assertFalse(action.isPending())
        runtime.shutdown()
    }

    @Test
    fun `interruption preserves attempt and pending state then can reconcile`() {
        val action = pendingAction("interrupted")
        val clock = FakeClock()
        val workers = SwitchableExecutor()
        val wakeups = RecordingWakeups(clock)
        val reports = AtomicInteger()
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = wakeups,
                reporter =
                    CnbMetadataReporter { _, _ ->
                        if (reports.getAndIncrement() == 0) {
                            throw CnbApiException(
                                "interrupted",
                                retryable = true,
                                cause = InterruptedException("controller shutdown"),
                            )
                        }
                        CnbBuildMetadataReportResult("comment-1")
                    },
                clockMillis = clock::now,
            )

        runtime.scheduleItem(null, action)
        workers.runNext()
        // executeOne restores the worker interrupt flag. Clear it on this synthetic test worker.
        assertTrue(Thread.interrupted())

        assertTrue(action.isPending())
        assertEquals(0, runtime.retryAttempt(action))
        assertEquals(1, wakeups.activeCount())
        assertEquals(CnbBuildMetadataDispatchRuntime.INTERRUPTED_RETRY_DELAY_MILLIS, wakeups.singleActive().delayMillis)

        clock.advance(CnbBuildMetadataDispatchRuntime.INTERRUPTED_RETRY_DELAY_MILLIS)
        wakeups.runDue()
        workers.runNext()

        assertFalse(action.isPending())
        assertEquals(2, reports.get())
        runtime.shutdown()
    }

    @Test
    fun `shutdown interrupts in-flight report without marking action reported`() {
        val action = pendingAction("shutdown")
        val workers = newMetadataWorkerExecutor()
        val started = CountDownLatch(1)
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = RecordingWakeups(FakeClock()),
                reporter =
                    CnbMetadataReporter { _, _ ->
                        started.countDown()
                        CountDownLatch(1).await()
                        error("report should only leave the wait through interruption")
                    },
            )

        runtime.scheduleItem(null, action)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        runtime.shutdown()

        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(action.isPending())
        assertNull(runtime.retryAttempt(action))
    }

    @Test
    fun `cancelled earlier deadlines are physically removed from plugin wakeup queue`() {
        val wakeups = newMetadataWakeupExecutor()
        var scheduled = wakeups.schedule(TimeUnit.HOURS.toMillis(2), Runnable {})
        try {
            assertTrue(wakeups.removesCancelledTasks())
            assertFalse(wakeups.executesDelayedTasksAfterShutdown())
            assertFalse(wakeups.continuesPeriodicTasksAfterShutdown())
            repeat(1_000) { index ->
                scheduled.cancel()
                scheduled =
                    wakeups.schedule(
                        TimeUnit.HOURS.toMillis(1) - index,
                        Runnable {},
                    )
                assertEquals(1, wakeups.queuedTaskCount())
            }
        } finally {
            scheduled.cancel()
            wakeups.shutdown()
        }
        assertTrue(wakeups.isShutdown())
        assertEquals(0, wakeups.queuedTaskCount())
    }

    @Test
    fun `runtime shutdown clears delayed wakeup without marking action reported`() {
        val action = pendingAction("delayed-shutdown")
        val wakeups = newMetadataWakeupExecutor()
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = SwitchableExecutor(),
                wakeups = wakeups,
                reporter = CnbMetadataReporter { _, _ -> error("delayed report must not run") },
            )

        assertTrue(runtime.scheduleItem(null, action, TimeUnit.HOURS.toSeconds(1)))
        assertEquals(1, wakeups.queuedTaskCount())

        runtime.shutdown()

        assertTrue(wakeups.isShutdown())
        assertEquals(0, wakeups.queuedTaskCount())
        assertTrue(action.isPending())
        assertNull(runtime.retryAttempt(action))
    }

    @Test
    fun `production worker pool uses bounded daemon plugin threads with core timeout`() {
        val executor = newMetadataWorkerExecutor()
        val observed = arrayOfNulls<Thread>(1)
        val completed = CountDownLatch(1)
        try {
            executor.execute {
                observed[0] = Thread.currentThread()
                completed.countDown()
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(requireNotNull(observed[0]).isDaemon)
            assertTrue(requireNotNull(observed[0]).name.startsWith("jenkins-cnb-build-metadata-"))
            assertTrue(executor.allowsCoreThreadTimeOut())
            assertTrue(executor.queue is java.util.concurrent.ArrayBlockingQueue<*>)
            assertTrue(executor.queue.remainingCapacity() > 0)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun pendingAction(identity: String): CnbBuildMetadataAction {
        val action = CnbBuildMetadataAction("run:folder/job#$identity")
        action.advance(
            CnbBuildMetadataTarget(
                serverId = "cnb-cool",
                repository = "team/project",
                sha = "0123456789abcdef0123456789abcdef01234567",
                pullRequestNumber = null,
                context = "folder/job",
                credentialsId = null,
            ),
            CnbBuildMetadataState.RUNNING,
            "folder/job #$identity",
            "job/folder/job/$identity/",
        )
        return action
    }

    private class FakeClock {
        private var millis = 0L

        fun now(): Long = millis

        fun advance(incrementMillis: Long) {
            millis += incrementMillis
        }
    }

    private class MutableHealthClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableHealthClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private data class Wakeup(
        val delayMillis: Long,
        val dueMillis: Long,
        val task: Runnable,
        var cancelled: Boolean = false,
        var fired: Boolean = false,
    )

    private class RecordingWakeups(
        private val clock: FakeClock,
    ) : CnbMetadataWakeupScheduler {
        private val wakeups = mutableListOf<Wakeup>()
        var scheduleCalls = 0
            private set

        override fun schedule(
            delayMillis: Long,
            wakeup: Runnable,
        ): CnbMetadataScheduledWakeup {
            scheduleCalls++
            val entry = Wakeup(delayMillis, clock.now() + delayMillis, wakeup)
            wakeups += entry
            return CnbMetadataScheduledWakeup { entry.cancelled = true }
        }

        fun activeCount(): Int = wakeups.count { !it.cancelled && !it.fired }

        fun singleActive(): Wakeup = wakeups.single { !it.cancelled && !it.fired }

        fun runDue() {
            val entry = wakeups.first { !it.cancelled && !it.fired && it.dueMillis <= clock.now() }
            entry.fired = true
            entry.task.run()
        }
    }

    private class SwitchableExecutor(
        @Volatile var reject: Boolean = false,
    ) : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown || reject) throw RejectedExecutionException(if (shutdown) "shutdown" else "full")
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return buildList {
                while (tasks.isNotEmpty()) add(tasks.removeFirst())
            }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }
}

@WithJenkins
class CnbBuildMetadataPersistenceTest {
    @Test
    fun `save failure restores pending state and retries with bounded backoff`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-save-retry")
        val run = jenkins.buildAndAssertSuccess(project)
        val action = pendingRunAction("save-retry")
        run.addAction(action)
        run.save()
        val clock = TestClock()
        val workers = TestExecutor()
        val wakeups = TestWakeups(clock)
        val knownCommentIds = mutableListOf<String?>()
        var saves = 0
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = wakeups,
                reporter =
                    CnbMetadataReporter { snapshot, _ ->
                        knownCommentIds += snapshot.knownCommentId
                        CnbBuildMetadataReportResult("comment-1")
                    },
                clockMillis = clock::now,
                saveRun = { candidate ->
                    saves++
                    if (saves == 1) throw IOException("injected save failure")
                    candidate.save()
                },
            )

        assertTrue(runtime.scheduleRun(run, action))
        workers.runNext()

        assertTrue(action.isPending())
        assertEquals(1, runtime.pendingCount())
        assertEquals(1, wakeups.activeCount())
        assertEquals(1_000L, wakeups.singleActive().delayMillis)

        clock.advance(1_000)
        wakeups.runDue()
        workers.runNext()

        assertFalse(action.isPending())
        assertEquals(listOf(null, "comment-1"), knownCommentIds)
        assertEquals(2, saves)
        run.reload()
        assertFalse(requireNotNull(run.getAction(CnbBuildMetadataAction::class.java)).isPending())
        runtime.shutdown()
    }

    @Test
    fun `restart reloads the old pending action when acknowledgement save failed`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-save-restart")
        val run = jenkins.buildAndAssertSuccess(project)
        val action = pendingRunAction("save-restart")
        run.addAction(action)
        run.save()
        val workers = TestExecutor()
        val runtime =
            CnbBuildMetadataDispatchRuntime(
                workers = workers,
                wakeups = TestWakeups(TestClock()),
                reporter = CnbMetadataReporter { _, _ -> CnbBuildMetadataReportResult("comment-before-crash") },
                saveRun = { throw IOException("injected save failure") },
            )

        assertTrue(runtime.scheduleRun(run, action))
        workers.runNext()
        assertTrue(action.isPending())
        runtime.shutdown()

        run.reload()
        assertTrue(requireNotNull(run.getAction(CnbBuildMetadataAction::class.java)).isPending())
    }

    private fun pendingRunAction(identity: String): CnbBuildMetadataAction {
        val action = CnbBuildMetadataAction("run:$identity")
        action.advance(
            CnbBuildMetadataTarget(
                serverId = "cnb-cool",
                repository = "team/project",
                sha = "0123456789abcdef0123456789abcdef01234567",
                pullRequestNumber = "7",
                context = "folder/job",
                credentialsId = null,
            ),
            CnbBuildMetadataState.SUCCESS,
            identity,
            "job/$identity/",
        )
        return action
    }

    private class TestClock {
        private var millis = 0L

        fun now(): Long = millis

        fun advance(incrementMillis: Long) {
            millis += incrementMillis
        }
    }

    private data class TestWakeup(
        val delayMillis: Long,
        val dueMillis: Long,
        val task: Runnable,
        var cancelled: Boolean = false,
        var fired: Boolean = false,
    )

    private class TestWakeups(
        private val clock: TestClock,
    ) : CnbMetadataWakeupScheduler {
        private val entries = mutableListOf<TestWakeup>()

        override fun schedule(
            delayMillis: Long,
            wakeup: Runnable,
        ): CnbMetadataScheduledWakeup {
            val entry = TestWakeup(delayMillis, clock.now() + delayMillis, wakeup)
            entries += entry
            return CnbMetadataScheduledWakeup { entry.cancelled = true }
        }

        fun activeCount(): Int = entries.count { !it.cancelled && !it.fired }

        fun singleActive(): TestWakeup = entries.single { !it.cancelled && !it.fired }

        fun runDue() {
            val entry = entries.first { !it.cancelled && !it.fired && it.dueMillis <= clock.now() }
            entry.fired = true
            entry.task.run()
        }
    }

    private class TestExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("shutdown")
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return buildList {
                while (tasks.isNotEmpty()) add(tasks.removeFirst())
            }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }
}
