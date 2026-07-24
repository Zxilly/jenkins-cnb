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
import java.util.logging.Level
import java.util.logging.Logger

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

    @Volatile
    @Transient
    private var attemptStarted: Boolean = false

    @Transient
    private var executor: Executor? = executor

    private var phase: Phase = Phase.READY
    private var attempt: Int = 0
    private var successfulValue: Any? = null
    private var terminalFailure: Throwable? = null

    protected abstract fun runAttempt(
        attempt: Int,
        resumed: Boolean,
    ): T

    /** Runs only after the successful result has reached a durable Pipeline checkpoint. */
    @Throws(IOException::class, InterruptedException::class)
    protected open fun afterCheckpoint(value: T) = Unit

    /** Cleans attempt-scoped artifacts after failure or cancellation. Must be idempotent. */
    @Throws(IOException::class, InterruptedException::class)
    protected open fun afterUnsuccessfulCompletion() = Unit

    final override fun start(): Boolean {
        schedule(resumed = false)
        return false
    }

    final override fun onResume() {
        val completed =
            synchronized(this) {
                task = null
                threadName = null
                if (phase == Phase.CHECKPOINTING || phase == Phase.SUCCEEDED) {
                    successfulValue
                } else {
                    NOT_COMPLETED
                }
            }
        if (completed !== NOT_COMPLETED) {
            deliverCheckpointed(completed)
            return
        }
        val failure =
            synchronized(this) {
                if (phase == Phase.STOPPED || phase == Phase.FAILED) {
                    terminalFailure ?: IOException("$operationName did not persist its terminal failure")
                } else {
                    null
                }
        }
        if (failure != null) {
            cleanupAfterUnsuccessfulCompletion(failure)
            context.onFailure(failure)
            return
        }
        try {
            schedule(resumed = true)
        } catch (failure: Throwable) {
            cleanupAfterUnsuccessfulCompletion(failure)
            context.onFailure(failure)
        }
    }

    final override fun stop(cause: Throwable) {
        val stopped =
            synchronized(this) {
                if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.STOPPED) return
                val cleanupWithoutRunningTask =
                    !attemptStarted && (task != null || phase != Phase.READY)
                phase = Phase.STOPPED
                stopCause = cause
                terminalFailure = cause
                StoppedAttempt(task, cleanupWithoutRunningTask)
            }
        stopped.task?.cancel(true)
        if (stopped.cleanupQueuedAttempt) {
            cleanupAfterUnsuccessfulCompletion(cause)
        }
        context.onFailure(cause)
    }

    final override fun blocksRestart(): Boolean = false

    final override fun getStatus(): String =
        synchronized(this) {
            when (phase) {
                Phase.READY -> "$operationName is waiting to be scheduled"
                Phase.RUNNING -> "$operationName attempt $attempt is running${threadName?.let { " on $it" }.orEmpty()}"
                Phase.CHECKPOINTING -> "$operationName completed remotely and is being checkpointed"
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
            if (phase == Phase.CHECKPOINTING || phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.STOPPED) return
            phase = Phase.RUNNING
            attemptStarted = false
            attempt++
            val scheduledAttempt = attempt
            scheduledTask =
                FutureTask {
                    val shouldRun =
                        synchronized(this) {
                            if (phase == Phase.STOPPED) {
                                false
                            } else {
                                attemptStarted = true
                                true
                            }
                        }
                    if (!shouldRun) return@FutureTask
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
                    if (phase != Phase.STOPPED) {
                        phase = Phase.FAILED
                        terminalFailure = IOException("Could not schedule $operationName", failure)
                    }
                }
            }
            throw IOException("Could not schedule $operationName", failure)
        }
    }

    private fun completeSuccessfully(value: T) {
        val stopped =
            synchronized(this) {
                if (phase == Phase.STOPPED) {
                    true
                } else {
                    successfulValue = value
                    phase = Phase.CHECKPOINTING
                    false
                }
            }
        if (stopped) {
            cleanupAfterUnsuccessfulCompletion(stopCause)
            return
        }
        try {
            context.saveState().get(CHECKPOINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            val checkpointFailure =
                synchronized(this) {
                    if (phase == Phase.STOPPED) {
                        null
                    } else {
                        IOException("Could not checkpoint the completed CNB transfer", failure).also {
                            phase = Phase.FAILED
                            terminalFailure = it
                        }
                    }
                }
            if (checkpointFailure == null) {
                cleanupAfterUnsuccessfulCompletion(stopCause)
                return
            }
            cleanupAfterUnsuccessfulCompletion(checkpointFailure)
            context.onFailure(checkpointFailure)
            return
        }
        deliverCheckpointed(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deliverCheckpointed(value: Any?) {
        try {
            afterCheckpoint(value as T)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            LOGGER.log(
                Level.WARNING,
                "Could not clean up $operationName after its successful Pipeline checkpoint",
                failure,
            )
        }
        var cleanupStopped = false
        var stoppedFailure: Throwable? = null
        val shouldReport =
            synchronized(this) {
                when (phase) {
                    Phase.CHECKPOINTING -> {
                        phase = Phase.SUCCEEDED
                        true
                    }

                    Phase.SUCCEEDED -> true
                    Phase.STOPPED -> {
                        cleanupStopped = true
                        stoppedFailure = stopCause
                        false
                    }

                    else -> false
                }
            }
        if (shouldReport) {
            context.onSuccess(value)
        } else if (cleanupStopped) {
            cleanupAfterUnsuccessfulCompletion(stoppedFailure)
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
                    terminalFailure = failure
                    true
                }
            }
        cleanupAfterUnsuccessfulCompletion(if (shouldReport) failure else stopCause)
        if (shouldReport) context.onFailure(failure)
    }

    private fun cleanupAfterUnsuccessfulCompletion(primaryFailure: Throwable?) {
        var restoreInterrupt = Thread.interrupted()
        try {
            afterUnsuccessfulCompletion()
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure is InterruptedException) restoreInterrupt = true
            primaryFailure?.addSuppressed(cleanupFailure)
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    private enum class Phase {
        READY,
        RUNNING,
        CHECKPOINTING,
        SUCCEEDED,
        FAILED,
        STOPPED,
    }

    private data class StoppedAttempt(
        val task: Future<*>?,
        val cleanupQueuedAttempt: Boolean,
    )

    companion object {
        private const val serialVersionUID = 1L
        private const val CHECKPOINT_TIMEOUT_SECONDS = 30L
        private val NOT_COMPLETED = Any()
        private val LOGGER = Logger.getLogger(CnbRestartableAsyncStepExecution::class.java.name)
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
