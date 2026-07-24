package dev.zxilly.jenkins.cnb.trigger

import hudson.Extension
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.RunListener
import jenkins.model.ParameterizedJobMixIn
import java.io.IOException

/** Applies the configured Classic trigger description without overriding existing metadata. */
@Extension
class CnbBuildDescriptionRunListener : RunListener<Run<*, *>>() {
    override fun onStarted(
        run: Run<*, *>,
        listener: TaskListener,
    ) {
        if (!run.description.isNullOrBlank()) return
        val trigger =
            try {
                ParameterizedJobMixIn.getTrigger(run.parent, CnbPushTrigger::class.java)
            } catch (_: RuntimeException) {
                null
            } ?: return
        if (!trigger.isSetBuildDescription()) return
        val cause = run.getCause(CnbPushCause::class.java) ?: return
        val description = cause.shortDescription.takeIf(String::isNotBlank) ?: return
        try {
            run.description = description
        } catch (_: IOException) {
            listener.logger.println("Failed to set the CNB build description")
        }
    }
}
