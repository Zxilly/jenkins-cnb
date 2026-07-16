package dev.zxilly.jenkins.cnb.config

import dev.zxilly.jenkins.cnb.Messages
import hudson.Extension
import jenkins.model.GlobalConfiguration
import org.jenkinsci.Symbol
import org.kohsuke.stapler.DataBoundSetter
import java.util.LinkedHashMap

@Extension
@Symbol("cnb")
class CnbGlobalConfiguration : GlobalConfiguration() {
    private var servers: List<CnbServer> = arrayListOf(CnbServer.defaultServer())
    var organizationReconciliationEnabled: Boolean = true
        private set
    var organizationReconciliationIntervalSeconds: Int = DEFAULT_ORGANIZATION_RECONCILIATION_INTERVAL_SECONDS
        private set

    init {
        load()
        if (servers.isEmpty()) {
            servers = arrayListOf(CnbServer.defaultServer())
        }
    }

    override fun getDisplayName(): String = Messages.CnbGlobalConfiguration_DisplayName()

    fun getServers(): List<CnbServer> = servers.toList()

    @DataBoundSetter
    fun setOrganizationReconciliationEnabled(value: Boolean) {
        organizationReconciliationEnabled = value
        save()
    }

    @DataBoundSetter
    fun setOrganizationReconciliationIntervalSeconds(value: Int) {
        organizationReconciliationIntervalSeconds =
            value.coerceIn(
                MIN_ORGANIZATION_RECONCILIATION_INTERVAL_SECONDS,
                MAX_ORGANIZATION_RECONCILIATION_INTERVAL_SECONDS,
            )
        save()
    }

    @DataBoundSetter
    fun setServers(value: List<CnbServer>?) {
        val unique = LinkedHashMap<String, CnbServer>()
        value.orEmpty().forEach { server ->
            server.validateConfiguration()
            require(unique.put(server.id, server) == null) { "Duplicate CNB server ID: ${server.id}" }
        }
        servers =
            if (unique.isEmpty()) {
                arrayListOf(CnbServer.defaultServer())
            } else {
                ArrayList(unique.values)
            }
        save()
    }

    fun findServer(id: String): CnbServer =
        servers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown CNB server profile: $id")

    companion object {
        const val DEFAULT_ORGANIZATION_RECONCILIATION_INTERVAL_SECONDS = 6 * 60 * 60
        const val MIN_ORGANIZATION_RECONCILIATION_INTERVAL_SECONDS = 15 * 60
        const val MAX_ORGANIZATION_RECONCILIATION_INTERVAL_SECONDS = 24 * 60 * 60

        @JvmStatic
        fun get(): CnbGlobalConfiguration =
            requireNotNull(GlobalConfiguration.all().get(CnbGlobalConfiguration::class.java)) {
                "CNB global configuration extension is not loaded"
            }
    }
}
