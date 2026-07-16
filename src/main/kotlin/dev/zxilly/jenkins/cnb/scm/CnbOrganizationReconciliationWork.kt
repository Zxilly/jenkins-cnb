package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import hudson.Extension
import hudson.model.AsyncPeriodicWork
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.model.Queue
import hudson.model.TaskListener
import jenkins.branch.OrganizationFolder
import jenkins.model.Jenkins
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

/** Repairs missed new/deleted repository events with a bounded periodic namespace rescan. */
@Extension
class CnbOrganizationReconciliationWork : AsyncPeriodicWork("CNB organization reconciliation") {
    override fun getRecurrencePeriod(): Long = CHECK_INTERVAL.toMillis()

    override fun getInitialDelay(): Long = CHECK_INTERVAL.toMillis()

    override fun execute(listener: TaskListener) {
        reconcile(listener)
    }

    internal fun reconcile(
        listener: TaskListener,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        val jenkins = Jenkins.get()
        if (jenkins.isQuietingDown || jenkins.isTerminating) return 0
        val configuration = CnbGlobalConfiguration.get()
        if (!configuration.organizationReconciliationEnabled) return 0
        val intervalMillis = Duration.ofSeconds(configuration.organizationReconciliationIntervalSeconds.toLong()).toMillis()
        var scheduledCount = 0

        for (folder in jenkins.getAllItems(OrganizationFolder::class.java)) {
            try {
                if (!isDue(folder, nowMillis, intervalMillis)) continue
                val quietSeconds = MIN_QUIET_SECONDS + Math.floorMod(folder.fullName.hashCode(), QUIET_JITTER_SECONDS)
                val queued =
                    folder.scheduleBuild2(
                        quietSeconds,
                        CauseAction(CnbOrganizationReconciliationCause()),
                    )
                if (queued == null) {
                    LOGGER.log(Level.FINE, "CNB Organization Folder reconciliation was not queued for {0}", folder.fullName)
                } else {
                    scheduledCount++
                }
            } catch (failure: RuntimeException) {
                listener.error(
                    "Could not schedule CNB Organization Folder reconciliation for %s (%s)",
                    folder.fullName,
                    failure.javaClass.simpleName,
                )
                LOGGER.log(Level.FINE, "CNB Organization Folder reconciliation scheduling failure", failure)
            }
        }
        return scheduledCount
    }

    private fun isDue(
        folder: OrganizationFolder,
        nowMillis: Long,
        intervalMillis: Long,
    ): Boolean {
        if (!folder.isBuildable || folder.getSCMNavigators().none { it is CnbSCMNavigator }) return false
        val computation = folder.computation
        if (computation.isBuilding || Queue.getInstance().contains(folder)) return false
        val lastScanMillis = computation.timestamp.timeInMillis
        return lastScanMillis <= 0L || nowMillis - lastScanMillis >= intervalMillis
    }

    companion object {
        private val CHECK_INTERVAL = Duration.ofMinutes(5)
        private const val MIN_QUIET_SECONDS = 5
        private const val QUIET_JITTER_SECONDS = 5 * 60
        private val LOGGER = Logger.getLogger(CnbOrganizationReconciliationWork::class.java.name)
    }
}

internal class CnbOrganizationReconciliationCause : Cause() {
    override fun getShortDescription(): String = "Scheduled CNB Organization Folder consistency scan"

    companion object {
        private const val serialVersionUID = 1L
    }
}
