package dev.zxilly.jenkins.cnb.health

import hudson.Extension
import hudson.model.AdministrativeMonitor
import hudson.security.Permission
import jakarta.servlet.http.HttpServletResponse
import jenkins.model.Jenkins
import org.kohsuke.stapler.StaplerRequest2
import org.kohsuke.stapler.StaplerResponse2
import org.kohsuke.stapler.interceptor.RequirePOST

/** Non-dismissible warning for a recent CNB operation that has not subsequently recovered. */
@Extension
class CnbOperationalHealthMonitor
    @JvmOverloads
    constructor(
        private val health: CnbOperationalHealth = CnbOperationalHealth.get(),
    ) : AdministrativeMonitor(MONITOR_ID) {
        override fun getDisplayName(): String = "CNB operational health warning"

        override fun isActivated(): Boolean = health.snapshot().hasRecentUnresolvedFailures()

        override fun isSecurity(): Boolean = false

        override fun isEnabled(): Boolean = true

        fun isDismissible(): Boolean = false

        /** Deliberately ignore programmatic dismissal; a successful operation resolves the warning. */
        override fun disable(value: Boolean) = Unit

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getRequiredPermission(): Permission = Jenkins.ADMINISTER

        /** The inherited dismissal route exists in core, so make it explicitly non-mutating. */
        @RequirePOST
        override fun doDisable(
            request: StaplerRequest2,
            response: StaplerResponse2,
        ) {
            checkRequiredPermission()
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "This monitor cannot be dismissed")
        }

        companion object {
            const val MONITOR_ID = "cnb-operational-health"
        }
    }
