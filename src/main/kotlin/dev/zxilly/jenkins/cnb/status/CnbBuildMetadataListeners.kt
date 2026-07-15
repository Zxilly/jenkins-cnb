package dev.zxilly.jenkins.cnb.status

import hudson.Extension
import hudson.model.Queue
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.RunListener
import hudson.model.queue.QueueListener

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
class CnbBuildMetadataRunListener : RunListener<Run<*, *>>() {
    override fun onInitialize(run: Run<*, *>) {
        CnbBuildMetadataService.reportRun(run, CnbBuildMetadataState.QUEUED)
    }

    override fun onStarted(
        run: Run<*, *>,
        listener: TaskListener,
    ) {
        CnbBuildMetadataService.reportRun(run, CnbBuildMetadataState.RUNNING, listener = listener)
    }

    override fun onCompleted(
        run: Run<*, *>,
        listener: TaskListener,
    ) {
        CnbBuildMetadataService.reportRun(
            run,
            CnbBuildMetadataState.fromResult(run.result),
            listener = listener,
        )
    }

    override fun onFinalized(run: Run<*, *>) {
        CnbBuildMetadataService.reportRun(run, CnbBuildMetadataState.fromResult(run.result))
    }
}
