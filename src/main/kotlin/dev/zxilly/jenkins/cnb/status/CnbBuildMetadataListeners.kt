package dev.zxilly.jenkins.cnb.status

import hudson.Extension
import hudson.model.Item
import hudson.model.Queue
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.ItemListener
import hudson.model.listeners.RunListener
import hudson.model.queue.QueueListener
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

@Extension
class CnbBuildMetadataQueueListener : QueueListener() {
    override fun onEnterWaiting(item: Queue.WaitingItem) {
        CnbBuildMetadataService.reportQueued(item)
        CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX.observe(item)
    }

    override fun onEnterBlocked(item: Queue.BlockedItem) {
        CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX.observe(item)
    }

    override fun onEnterBuildable(item: Queue.BuildableItem) {
        CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX.observe(item)
    }

    override fun onLeft(item: Queue.LeftItem) {
        if (item.isCancelled) {
            CnbBuildMetadataService.reportCancelled(item)
        }
        CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX.remove(item)
    }
}

@Extension
class CnbCancelledBuildMetadataItemListener : ItemListener() {
    override fun onLocationChanged(
        item: Item,
        oldFullName: String,
        newFullName: String,
    ) {
        try {
            CnbCancelledBuildMetadataStores.current().relocate(oldFullName, newFullName, item)
        } catch (failure: IOException) {
            LOGGER.log(Level.WARNING, "Could not persist CNB cancelled metadata after an item move", failure)
        }
    }

    override fun onDeleted(item: Item) {
        try {
            val affected = CnbCancelledBuildMetadataStores.current().tombstoneCredentialContext(item.fullName)
            if (affected > 0) {
                LOGGER.warning(
                    "Discarding $affected pending CNB metadata report(s) because their credential context was deleted",
                )
            }
        } catch (failure: IOException) {
            LOGGER.log(Level.WARNING, "Could not persist deleted CNB metadata credential contexts", failure)
        }
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(CnbCancelledBuildMetadataItemListener::class.java.name)
    }
}

@Extension
class CnbBuildMetadataRunListener : RunListener<Run<*, *>>() {
    override fun onInitialize(run: Run<*, *>) {
        CnbBuildMetadataService.reportRunLifecycle(run, CnbBuildMetadataState.QUEUED)
    }

    override fun onStarted(
        run: Run<*, *>,
        listener: TaskListener,
    ) {
        CnbBuildMetadataService.reportRunLifecycle(run, CnbBuildMetadataState.RUNNING, listener)
    }

    override fun onCompleted(
        run: Run<*, *>,
        listener: TaskListener,
    ) {
        CnbBuildMetadataService.reportRunLifecycle(
            run,
            CnbBuildMetadataState.fromResult(run.result),
            listener,
        )
    }

    override fun onFinalized(run: Run<*, *>) {
        CnbBuildMetadataService.reportRunLifecycle(run, CnbBuildMetadataState.fromResult(run.result))
    }
}
