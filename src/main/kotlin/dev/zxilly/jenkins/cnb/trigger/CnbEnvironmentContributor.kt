package dev.zxilly.jenkins.cnb.trigger

import hudson.EnvVars
import hudson.Extension
import hudson.model.EnvironmentContributor
import hudson.model.Run
import hudson.model.TaskListener

@Extension
class CnbEnvironmentContributor : EnvironmentContributor() {
    override fun buildEnvironmentFor(
        run: Run<*, *>,
        envs: EnvVars,
        listener: TaskListener,
    ) {
        val variables =
            run.getCause(CnbPushCause::class.java)?.buildVariables()
                ?: run.getCause(CnbRepositoryEventCause::class.java)?.buildVariables()
                ?: return
        envs.overrideAll(variables)
    }
}
