package dev.zxilly.jenkins.cnb.status

import hudson.model.Job
import hudson.model.Queue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

class CnbBuildMetadataRecoveryGateTest {
    @Test
    fun `no recovery request does not enumerate queue or jobs`() {
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        var queueEnumerations = 0
        var jobEnumerations = 0

        val stats =
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = {
                    queueEnumerations++
                    emptyList<Queue.Item>().iterator()
                },
                jobs = {
                    jobEnumerations++
                    emptyList<Job<*, *>>().iterator()
                },
                scheduleQueue = { _, _ -> error("nothing should be scheduled") },
                scheduleRun = { _, _ -> error("nothing should be scheduled") },
            )

        assertEquals(0, stats.generation)
        assertEquals(0, queueEnumerations)
        assertEquals(0, jobEnumerations)
        assertNull(requests.pendingGeneration())
    }

    @Test
    fun `new generation cannot be cleared by an older scan`() {
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val scanning = requests.request()
        val newer = requests.request()

        assertFalse(requests.completeIfUnchanged(scanning))
        assertEquals(newer, requests.pendingGeneration())
        assertTrue(requests.completeIfUnchanged(newer))
        assertNull(requests.pendingGeneration())
    }
}

@WithJenkins
class CnbBuildMetadataReconciliationWorkTest {
    @Test
    fun `one request advances in bounded ticks then becomes idle`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-history")
        val builds = (1..3).map { jenkins.buildAndAssertSuccess(project) }
        val pending = pendingAction("oldest")
        builds.first().addAction(pending)
        val scheduledBuilds = mutableListOf<Int>()
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        var jobEnumerations = 0
        requests.request()

        var ticks = 0
        while (requests.pendingGeneration() != null && ticks < 10) {
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = {
                    jobEnumerations++
                    listOf<Job<*, *>>(project).iterator()
                },
                maximumJobs = 1,
                maximumRuns = 1,
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { run, _ ->
                    scheduledBuilds += run.number
                    true
                },
            )
            ticks++
        }

        assertNull(requests.pendingGeneration())
        assertTrue(ticks in 2..10)
        assertEquals(1, jobEnumerations, "the lazy job iterator must be retained for the round")
        assertEquals(listOf(builds.first().number), scheduledBuilds)
        assertTrue(pending.isPending())

        work.recoverOnce(
            recoveryRequests = requests,
            queueItems = { error("idle recovery must not inspect the queue") },
            jobs = { error("idle recovery must not inspect jobs") },
            scheduleQueue = { _, _ -> error("idle recovery must not schedule") },
            scheduleRun = { _, _ -> error("idle recovery must not schedule") },
        )
    }

    @Test
    fun `overflow requested during a scan survives into a second round`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-generation")
        val build = jenkins.buildAndAssertSuccess(project)
        build.addAction(pendingAction("generation"))
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val firstGeneration = requests.request()
        var requestedDuringScan = false
        var jobEnumerations = 0

        val firstTick =
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = {
                    jobEnumerations++
                    listOf<Job<*, *>>(project).iterator()
                },
                maximumJobs = 2,
                maximumRuns = 2,
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { _, _ ->
                    if (!requestedDuringScan) {
                        requestedDuringScan = true
                        requests.request()
                    }
                    true
                },
            )

        assertEquals(firstGeneration, firstTick.generation)
        assertTrue(firstTick.roundCompleted)
        assertFalse(firstTick.requestCleared)
        assertNotNull(requests.pendingGeneration())

        val secondTick =
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = {
                    jobEnumerations++
                    listOf<Job<*, *>>(project).iterator()
                },
                maximumJobs = 2,
                maximumRuns = 2,
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { _, _ -> true },
            )

        assertTrue(secondTick.roundCompleted)
        assertTrue(secondTick.requestCleared)
        assertNull(requests.pendingGeneration())
        assertEquals(2, jobEnumerations)
    }

    @Test
    fun `large queue supplier is retained and consumed only to the per tick limit`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-large-queue")

        @Suppress("DEPRECATION")
        val item = requireNotNull(Queue.getInstance().schedule(project, 3_600, emptyList()))
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        var supplierCalls = 0
        var itemAccesses = 0
        var remaining = 10_000
        val iterator =
            object : Iterator<Queue.Item> {
                override fun hasNext(): Boolean = remaining > 0

                override fun next(): Queue.Item {
                    if (!hasNext()) throw NoSuchElementException()
                    remaining--
                    itemAccesses++
                    return item
                }
            }
        requests.request()

        try {
            repeat(2) {
                val stats =
                    work.recoverOnce(
                        recoveryRequests = requests,
                        queueItems = {
                            supplierCalls++
                            iterator
                        },
                        jobs = { emptyList<Job<*, *>>().iterator() },
                        maximumQueueItems = 17,
                        maximumJobs = 1,
                        maximumRuns = 1,
                        scheduleQueue = { _, _ -> error("synthetic item has no CNB Action") },
                        scheduleRun = { _, _ -> error("jobs must be empty") },
                    )
                assertEquals(17, stats.queueItemsInspected)
                assertFalse(stats.roundCompleted)
            }

            assertEquals(1, supplierCalls)
            assertEquals(34, itemAccesses)
            assertNotNull(requests.pendingGeneration())
        } finally {
            Queue.getInstance().cancel(item)
        }
    }

    @Test
    fun `queue index generation keeps initial items and defers newer IDs`(jenkins: JenkinsRule) {
        val firstProject = jenkins.createFreeStyleProject("metadata-queue-first")
        val secondProject = jenkins.createFreeStyleProject("metadata-queue-second")

        @Suppress("DEPRECATION")
        val first = requireNotNull(Queue.getInstance().schedule(firstProject, 3_600, emptyList()))
        val index = CnbBuildMetadataQueueRecoveryIndex()
        first.addAction(pendingAction("queue-first"))
        index.observe(first)
        val firstGeneration = index.snapshotIterator()

        @Suppress("DEPRECATION")
        val second = requireNotNull(Queue.getInstance().schedule(secondProject, 3_600, emptyList()))
        second.addAction(pendingAction("queue-second"))
        index.observe(second)

        try {
            assertEquals(listOf(first.id), firstGeneration.asSequence().map(Queue.Item::getId).toList())
            assertEquals(
                listOf(first.id, second.id),
                index
                    .snapshotIterator()
                    .asSequence()
                    .map(Queue.Item::getId)
                    .toList(),
            )
        } finally {
            index.remove(first)
            index.remove(second)
            Queue.getInstance().cancel(first)
            Queue.getInstance().cancel(second)
        }
    }

    @Test
    fun `queue index retains an item when a later metadata context is pending`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-queue-contexts")

        @Suppress("DEPRECATION")
        val item = requireNotNull(Queue.getInstance().schedule(project, 3_600, emptyList()))
        val reported = pendingAction("queue-reported")
        val pending = pendingAction("queue-pending")
        val reportedSnapshot = requireNotNull(reported.snapshot())
        reported.markReported(reportedSnapshot.version, null)
        item.addAction(reported)
        item.addAction(pending)
        val index = CnbBuildMetadataQueueRecoveryIndex()

        try {
            index.observe(item)

            assertEquals(1, index.size())
            assertEquals(listOf(item.id), index.snapshotIterator().asSequence().map(Queue.Item::getId).toList())

            val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
            val scheduled = mutableListOf<CnbBuildMetadataAction>()
            requests.request()
            val stats =
                CnbBuildMetadataReconciliationWork().recoverOnce(
                    recoveryRequests = requests,
                    queueItems = index::snapshotIterator,
                    jobs = { emptyList<Job<*, *>>().iterator() },
                    scheduleQueue = { _, action ->
                        scheduled += action
                        true
                    },
                    scheduleRun = { _, _ -> error("runs must be empty") },
                )

            assertEquals(listOf(pending), scheduled)
            assertEquals(1, stats.queueItemsInspected)
            assertEquals(1, stats.accepted)
        } finally {
            index.remove(item)
            Queue.getInstance().cancel(item)
        }
    }

    @Test
    fun `run recovery schedules a later pending metadata context`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-run-contexts")
        val run = jenkins.buildAndAssertSuccess(project)
        val reported = pendingAction("run-reported")
        val pending = pendingAction("run-pending")
        val reportedSnapshot = requireNotNull(reported.snapshot())
        reported.markReported(reportedSnapshot.version, null)
        run.addAction(reported)
        run.addAction(pending)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        val stats =
            CnbBuildMetadataReconciliationWork().recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = { listOf<Job<*, *>>(project).iterator() },
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { scheduledRun, action ->
                    assertEquals(run, scheduledRun)
                    scheduled += action
                    true
                },
            )

        assertEquals(listOf(pending), scheduled)
        assertEquals(1, stats.runsInspected)
        assertEquals(1, stats.accepted)
    }

    @Test
    fun `queue action budget resumes remaining contexts on later ticks`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-queue-budget")

        @Suppress("DEPRECATION")
        val item = requireNotNull(Queue.getInstance().schedule(project, 3_600, emptyList()))
        val actions = (1..3).map { pendingAction("queue-budget-$it") }
        actions.forEach(item::addAction)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        try {
            var ticks = 0
            while (requests.pendingGeneration() != null && ticks < 10) {
                val stats =
                    work.recoverOnce(
                        recoveryRequests = requests,
                        queueItems = { listOf<Queue.Item>(item).iterator() },
                        jobs = { emptyList<Job<*, *>>().iterator() },
                        maximumQueueItems = 1,
                        maximumActions = 1,
                        scheduleQueue = { _, action ->
                            scheduled += action
                            true
                        },
                        scheduleRun = { _, _ -> error("runs must be empty") },
                    )
                assertTrue(stats.actionsInspected <= 1)
                ticks++
            }

            assertNull(requests.pendingGeneration())
            assertEquals(actions, scheduled)
        } finally {
            Queue.getInstance().cancel(item)
        }
    }

    @Test
    fun `run action budget resumes remaining contexts on later ticks`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-run-budget")
        val run = jenkins.buildAndAssertSuccess(project)
        val actions = (1..3).map { pendingAction("run-budget-$it") }
        actions.forEach(run::addAction)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        var ticks = 0
        while (requests.pendingGeneration() != null && ticks < 10) {
            val stats =
                work.recoverOnce(
                    recoveryRequests = requests,
                    queueItems = { emptyList<Queue.Item>().iterator() },
                    jobs = { listOf<Job<*, *>>(project).iterator() },
                    maximumJobs = 1,
                    maximumRuns = 1,
                    maximumActions = 1,
                    scheduleQueue = { _, _ -> error("queue must be empty") },
                    scheduleRun = { scheduledRun, action ->
                        assertEquals(run, scheduledRun)
                        scheduled += action
                        true
                    },
                )
            assertTrue(stats.actionsInspected <= 1)
            ticks++
        }

        assertNull(requests.pendingGeneration())
        assertEquals(actions, scheduled)
    }

    private fun pendingAction(identity: String): CnbBuildMetadataAction {
        val action = CnbBuildMetadataAction("run:metadata-history#$identity")
        action.advance(
            CnbBuildMetadataTarget(
                serverId = "cnb-cool",
                repository = "team/project",
                sha = "0123456789abcdef0123456789abcdef01234567",
                pullRequestNumber = null,
                context = "metadata-history",
                credentialsId = null,
            ),
            CnbBuildMetadataState.SUCCESS,
            "metadata-history #$identity",
            "job/metadata-history/$identity/",
        )
        return action
    }
}
