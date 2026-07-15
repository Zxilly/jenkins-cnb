package dev.zxilly.jenkins.cnb.status

import hudson.init.Terminator

/** Stops plugin-owned reporting threads before the controller completes shutdown. */
@Terminator
fun shutdownCnbBuildMetadataReporting() {
    CnbBuildMetadataDispatcher.shutdown()
}
