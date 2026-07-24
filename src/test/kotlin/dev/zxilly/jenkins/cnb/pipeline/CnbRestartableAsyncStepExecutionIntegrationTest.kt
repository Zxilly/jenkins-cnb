package dev.zxilly.jenkins.cnb.pipeline

import hudson.model.Result
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension
import org.kohsuke.stapler.DataBoundConstructor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CnbRestartableAsyncStepExecutionIntegrationTest {
    @JvmField
    @RegisterExtension
    val jenkinsSession = JenkinsSessionExtension()

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `running transfer resumes once after a real controller session restart`() {
        CnbRestartProbeExecutor.reset()
        try {
            jenkinsSession.then { jenkins ->
                val descriptor = CnbRestartProbeStep.DescriptorImpl()
                StepDescriptor.all().add(descriptor)
                try {
                    val job = jenkins.createProject(WorkflowJob::class.java, JOB_NAME)
                    job.addProperty(DurabilityHintJobProperty(FlowDurabilityHint.MAX_SURVIVABILITY))
                    job.definition =
                        CpsFlowDefinition(
                            """
                            def result = cnbRestartProbe()
                            echo '${RESULT_PREFIX}' + result
                            """.trimIndent(),
                            true,
                        )

                    val run = requireNotNull(job.scheduleBuild2(0)).waitForStart()
                    val execution = CnbRestartProbeExecutor.awaitScheduledExecution()
                    assertTrue(run.isBuilding)
                    assertEquals("CNB restart probe attempt 1 is running", execution.status)

                    execution.context.saveState().get(10, TimeUnit.SECONDS)
                    assertTrue(CnbRestartProbeExecutor.cancelAndClear())
                    CnbRestartProbeExecutor.clearExecution(execution)
                } finally {
                    StepDescriptor.all().remove(descriptor)
                }
            }

            jenkinsSession.then { jenkins ->
                val job = requireNotNull(jenkins.jenkins.getItemByFullName(JOB_NAME, WorkflowJob::class.java))
                val run = requireNotNull(job.getBuildByNumber(1))
                jenkins.waitForCompletion(run)

                assertEquals(Result.SUCCESS, run.result)
                assertFalse(run.isBuilding)
                assertEquals(1, job.builds.count())
                val log = JenkinsRule.getLog(run)
                assertEquals(1, log.lineSequence().count { RESULT_LINE in it })

                val activeExecutions = AtomicInteger()
                StepExecution
                    .acceptAll(CnbRestartProbeExecution::class.java) { activeExecutions.incrementAndGet() }
                    .get(10, TimeUnit.SECONDS)
                assertEquals(0, activeExecutions.get())
                assertFalse(CnbRestartProbeExecutor.hasPendingTask())
            }
        } finally {
            CnbRestartProbeExecutor.reset()
        }
    }

    companion object {
        private const val JOB_NAME = "restartable-cnb-transfer"
        private const val RESULT_PREFIX = "CNB restart probe result: "
        private const val RESULT_LINE = "${RESULT_PREFIX}attempt=2,resumed=true"
    }
}

internal class CnbRestartProbeStep
    @DataBoundConstructor
    constructor() : Step() {
        override fun start(context: StepContext): StepExecution = CnbRestartProbeExecution(context).also(CnbRestartProbeExecutor::register)

        class DescriptorImpl : StepDescriptor() {
            override fun getFunctionName(): String = "cnbRestartProbe"

            override fun getDisplayName(): String = "Test CNB restartable execution"

            override fun getRequiredContext(): Set<Class<*>> = emptySet()
        }
    }

internal class CnbRestartProbeExecution(
    context: StepContext,
) : CnbRestartableAsyncStepExecution<String>(context, "CNB restart probe", CnbRestartProbeExecutor) {
    override fun runAttempt(
        attempt: Int,
        resumed: Boolean,
    ): String = "attempt=$attempt,resumed=$resumed"

    companion object {
        private const val serialVersionUID = 1L
    }
}

private object CnbRestartProbeExecutor : Executor {
    private val pendingTask = AtomicReference<Future<*>?>()
    private val execution = AtomicReference<CnbRestartProbeExecution?>()

    @Volatile
    private var taskReady = CountDownLatch(1)

    @Volatile
    private var executionReady = CountDownLatch(1)

    override fun execute(command: Runnable) {
        val future = command as? Future<*> ?: error("CNB restart probe expected a Future task")
        check(pendingTask.compareAndSet(null, future)) { "CNB restart probe already has a pending task" }
        taskReady.countDown()
    }

    fun register(candidate: CnbRestartProbeExecution) {
        check(execution.compareAndSet(null, candidate)) { "CNB restart probe already has an execution" }
        executionReady.countDown()
    }

    fun awaitScheduledExecution(): CnbRestartProbeExecution {
        check(executionReady.await(10, TimeUnit.SECONDS)) { "CNB restart probe execution was not created" }
        check(taskReady.await(10, TimeUnit.SECONDS)) { "CNB restart probe task was not scheduled" }
        return checkNotNull(execution.get()) { "CNB restart probe execution disappeared" }
    }

    fun clearExecution(candidate: CnbRestartProbeExecution) {
        check(execution.compareAndSet(candidate, null)) { "CNB restart probe execution changed unexpectedly" }
    }

    fun cancelAndClear(): Boolean {
        val task = pendingTask.getAndSet(null) ?: return false
        return task.cancel(false)
    }

    fun hasPendingTask(): Boolean = pendingTask.get() != null

    fun reset() {
        pendingTask.getAndSet(null)?.cancel(false)
        execution.set(null)
        taskReady = CountDownLatch(1)
        executionReady = CountDownLatch(1)
    }
}
