package dev.zxilly.jenkins.cnb.ui

import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealthSnapshot
import hudson.Extension
import hudson.model.ManagementLink
import hudson.security.Permission
import jakarta.servlet.ServletException
import jenkins.model.Jenkins
import org.kohsuke.stapler.Stapler
import org.kohsuke.stapler.StaplerProxy
import org.kohsuke.stapler.StaplerRequest2
import org.kohsuke.stapler.StaplerResponse2
import org.kohsuke.stapler.verb.GET
import java.io.IOException

/** Administrator-only, read-only view of recent CNB operational outcomes. */
@Extension
class CnbOperationalHealthManagementLink
    @JvmOverloads
    constructor(
        private val health: CnbOperationalHealth = CnbOperationalHealth.get(),
    ) : ManagementLink(),
        StaplerProxy {
        override fun getIconFileName(): String = "symbol-pulse-outline plugin-ionicons-api"

        override fun getDisplayName(): String = DISPLAY_NAME

        override fun getDescription(): String = "Recent webhook, polling, and status reporting outcomes for CNB integrations."

        override fun getUrlName(): String = URL_NAME

        override fun getRequiredPermission(): Permission = Jenkins.ADMINISTER

        override fun getCategory(): Category = Category.STATUS

        fun getSnapshot(): CnbOperationalHealthSnapshot = health.snapshot()

        override fun getTarget(): Any {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER)
            Stapler.getCurrentResponse2()?.let(::applyNoStoreHeaders)
            return this
        }

        @GET
        @Throws(IOException::class, ServletException::class)
        fun doIndex(
            request: StaplerRequest2,
            response: StaplerResponse2,
        ) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER)
            applyNoStoreHeaders(response)
            request.getView(this, "index.jelly").forward(request, response)
        }

        private fun applyNoStoreHeaders(response: StaplerResponse2) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate")
            response.setHeader("Pragma", "no-cache")
            response.setDateHeader("Expires", 0L)
        }

        companion object {
            const val URL_NAME = "cnb-health"
            const val DISPLAY_NAME = "CNB operational health"
            const val MONITOR_MESSAGE = "CNB operations recently failed."
        }
    }
