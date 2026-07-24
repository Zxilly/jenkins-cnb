package dev.zxilly.jenkins.cnb.pipeline

import hudson.security.ACL
import hudson.util.ClassLoaderSanityThreadFactory
import hudson.util.DaemonThreadFactory
import hudson.util.NamingThreadFactory
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Restartable asynchronous execution for bounded workspace transfers.
 *
 * An in-flight attempt is deliberately retried after controller resume. A successful result is
 * checkpointed before the Pipeline callback, so a restart in that final window completes from the
 * persisted result instead of repeating the remote operation.
 */
internal abstract class CnbRestartableAsyncStepExecution<T>(
    context: StepContext,
    private val operationName: String,
    executor: Executor = EXECUTOR,
) : StepExecution(context) {
    @Volatile
    @Transient
    private var task: Future<*>? = null

    @Volatile
    @Transient
    private var threadName: String? = null

    @Volatile
    @Transient
    private var stopCause: Throwable? = null

    @Transient
    private var executor: Executor? = executor

    private var phase: Phase = Phase.READY
    private var attempt: Int = 0
    private var successfulValue: Any? = null

    protected abstract fun runAttempt(
        attempt: Int,
        resumed: Boolean,
    ): T

    final override fun start(): Boolean {
        schedule(resumed = false)
        return false
    }

    final override fun onResume() {
        val completed =
            synchronized(this) {
                task = null
                threadName = null
                if (phase == Phase.SUCCEEDED) successfulValue else NOT_COMPLETED
            }
        if (completed !== NOT_COMPLETED) {
            context.onSuccess(completed)
            return
        }
        if (synchronized(this) { phase == Phase.STOPPED || phase == Phase.FAILED }) return
        try {
            schedule(resumed = true)
        } catch (failure: Throwable) {
            context.onFailure(failure)
        }
    }

    final override fun stop(cause: Throwable) {
        val active =
            synchronized(this) {
                if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.STOPPED) return
                phase = Phase.STOPPED
                stopCause = cause
                task
            }
        active?.cancel(true)
        context.onFailure(cause)
    }

    final override fun blocksRestart(): Boolean = false

    final override fun getStatus(): String =
        synchronized(this) {
            when (phase) {
                Phase.READY -> "$operationName is waiting to be scheduled"
                Phase.RUNNING -> "$operationName attempt $attempt is running${threadName?.let { " on $it" }.orEmpty()}"
                Phase.SUCCEEDED -> "$operationName completed and was checkpointed"
                Phase.FAILED -> "$operationName failed"
                Phase.STOPPED -> "$operationName was stopped"
            }
        }

    private fun schedule(resumed: Boolean) {
        val authentication = Jenkins.getAuthentication2()
        lateinit var scheduledTask: FutureTask<Unit>
        synchronized(this) {
            val existing = task
            if (existing != null && !existing.isDone) return
            if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.STOPPED) return
            phase = Phase.RUNNING
            attempt++
            val scheduledAttempt = attempt
            scheduledTask =
                FutureTask {
                    threadName = Thread.currentThread().name
                    try {
                        ACL.as2(authentication).use {
                            val value = runAttempt(scheduledAttempt, resumed)
                            completeSuccessfully(value)
                        }
                    } catch (failure: Throwable) {
                        if (failure is InterruptedException) Thread.currentThread().interrupt()
                        completeExceptionally(failure)
                    } finally {
                        threadName = null
                    }
                }
            task = scheduledTask
        }
        try {
            (executor ?: EXECUTOR.also { executor = it }).execute(scheduledTask)
        } catch (failure: RuntimeException) {
            synchronized(this) {
                if (task === scheduledTask) {
                    task = null
                    if (phase != Phase.STOPPED) phase = Phase.FAILED
                }
            }
            throw IOException("Could not schedule $operationName", failure)
        }
    }

    private fun completeSuccessfully(value: T) {
        synchronized(this) {
            if (phase == Phase.STOPPED) return
            successfulValue = value
            phase = Phase.SUCCEEDED
        }
        try {
            context.saveState().get(CHECKPOINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            synchronized(this) {
                if (phase != Phase.STOPPED) phase = Phase.FAILED
            }
            context.onFailure(IOException("Could not checkpoint the completed CNB transfer", failure))
            return
        }
        if (synchronized(this) { phase == Phase.SUCCEEDED }) {
            context.onSuccess(value)
        }
    }

    private fun completeExceptionally(failure: Throwable) {
        val shouldReport =
            synchronized(this) {
                if (phase == Phase.STOPPED) {
                    stopCause?.addSuppressed(failure)
                    false
                } else {
                    phase = Phase.FAILED
                    true
                }
            }
        if (shouldReport) context.onFailure(failure)
    }

    private enum class Phase {
        READY,
        RUNNING,
        SUCCEEDED,
        FAILED,
        STOPPED,
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val CHECKPOINT_TIMEOUT_SECONDS = 30L
        private val NOT_COMPLETED = Any()
        private val EXECUTOR: ThreadPoolExecutor = newCnbTransferExecutor()
    }
}

internal fun newCnbTransferExecutor(): ThreadPoolExecutor =
    ThreadPoolExecutor(
        4,
        4,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        NamingThreadFactory(
            ClassLoaderSanityThreadFactory(DaemonThreadFactory()),
            CnbRestartableAsyncStepExecution::class.java.name,
        ),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
