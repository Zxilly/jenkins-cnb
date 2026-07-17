package dev.zxilly.jenkins.cnb.config

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.common.StandardCredentials
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.Messages
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.HttpCnbClient
import dev.zxilly.jenkins.cnb.security.CnbEndpointPolicy
import hudson.Extension
import hudson.model.Describable
import hudson.model.Descriptor
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.model.Jenkins
import org.jenkinsci.Symbol
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.Serializable
import java.util.Locale

class CnbServer
    @DataBoundConstructor
    constructor(
        id: String,
        name: String,
        webUrl: String,
        apiUrl: String,
    ) : Describable<CnbServer>,
        Serializable {
        var id: String = normalizeId(id)
            private set
        var name: String = normalizeName(name, this.id)
            private set
        var webUrl: String = normalizeEndpoint("web URL", webUrl)
            private set
        var apiUrl: String = normalizeEndpoint("API URL", apiUrl)
            private set

        var credentialsId: String? = null
            private set
        var reportingCredentialsId: String? = null
            private set
        private var webhookCredentials: List<CnbWebhookCredentials>? = arrayListOf()
        var allowInsecureHttp: Boolean = false
            private set
        var allowPrivateNetwork: Boolean = false
            private set
        var eventPollingEnabled: Boolean = true
            private set
        var eventPollingIntervalSeconds: Int = 120
            private set
        var maxWebhookAgeSeconds: Int = 900
            private set
        var connectTimeoutSeconds: Int = 10
            private set
        var requestTimeoutSeconds: Int = 30
            private set
        var statusReportingMode: CnbStatusReportingMode? = CnbStatusReportingMode.BOTH
            private set
        var automaticBuildBadgeEnabled: Boolean = false
            private set
        var automaticBuildBadgeKey: String? = DEFAULT_BUILD_BADGE_KEY
            private set

        @DataBoundSetter
        fun setCredentialsId(value: String?) {
            credentialsId = value?.trim()?.ifEmpty { null }
        }

        @DataBoundSetter
        fun setReportingCredentialsId(value: String?) {
            reportingCredentialsId = value?.trim()?.ifEmpty { null }
        }

        fun getWebhookCredentials(): List<CnbWebhookCredentials> = webhookCredentials.orEmpty().toList()

        @DataBoundSetter
        fun setWebhookCredentials(value: List<CnbWebhookCredentials>?) {
            val unique = linkedMapOf<String, CnbWebhookCredentials>()
            value.orEmpty().forEach { credentials ->
                require(unique.put(credentials.repositoryPath, credentials) == null) {
                    "Duplicate CNB webhook repository: ${credentials.repositoryPath}"
                }
            }
            webhookCredentials = ArrayList(unique.values)
        }

        fun webhookCredentialsFor(repositoryPath: String): CnbWebhookCredentials? =
            webhookCredentials.orEmpty().firstOrNull { it.repositoryPath == repositoryPath.trim().trim('/') }

        @DataBoundSetter
        fun setAllowInsecureHttp(value: Boolean) {
            allowInsecureHttp = value
        }

        @DataBoundSetter
        fun setAllowPrivateNetwork(value: Boolean) {
            allowPrivateNetwork = value
        }

        @DataBoundSetter
        fun setEventPollingEnabled(value: Boolean) {
            eventPollingEnabled = value
        }

        @DataBoundSetter
        fun setEventPollingIntervalSeconds(value: Int) {
            eventPollingIntervalSeconds = value.coerceIn(60, 3600)
        }

        @DataBoundSetter
        fun setMaxWebhookAgeSeconds(value: Int) {
            maxWebhookAgeSeconds = value.coerceIn(60, 3600)
        }

        @DataBoundSetter
        fun setConnectTimeoutSeconds(value: Int) {
            connectTimeoutSeconds = value.coerceIn(1, 120)
        }

        @DataBoundSetter
        fun setRequestTimeoutSeconds(value: Int) {
            requestTimeoutSeconds = value.coerceIn(1, 600)
        }

        @DataBoundSetter
        fun setStatusReportingMode(value: CnbStatusReportingMode?) {
            statusReportingMode = value ?: CnbStatusReportingMode.BOTH
        }

        @DataBoundSetter
        fun setAutomaticBuildBadgeEnabled(value: Boolean) {
            automaticBuildBadgeEnabled = value
        }

        @DataBoundSetter
        fun setAutomaticBuildBadgeKey(value: String?) {
            automaticBuildBadgeKey = normalizeBuildBadgeKey(value)
        }

        fun normalizedApiUri() = CnbEndpointPolicy.validateAndNormalize(apiUrl, allowInsecureHttp, allowPrivateNetwork)

        fun normalizedWebUri() = CnbEndpointPolicy.validateAndNormalize(webUrl, allowInsecureHttp, allowPrivateNetwork)

        internal fun validateConfiguration() {
            id = normalizeId(id)
            name = normalizeName(name, id)
            webUrl = normalizeEndpoint("web URL", webUrl)
            apiUrl = normalizeEndpoint("API URL", apiUrl)
            normalizedWebUri()
            normalizedApiUri()
        }

        fun readResolve(): Any {
            id = normalizeId(id)
            name = normalizeName(name, id)
            webUrl = normalizeEndpoint("web URL", webUrl)
            apiUrl = normalizeEndpoint("API URL", apiUrl)
            eventPollingIntervalSeconds = eventPollingIntervalSeconds.coerceIn(60, 3600)
            maxWebhookAgeSeconds = maxWebhookAgeSeconds.coerceIn(60, 3600)
            connectTimeoutSeconds = connectTimeoutSeconds.coerceIn(1, 120)
            requestTimeoutSeconds = requestTimeoutSeconds.coerceIn(1, 600)
            setWebhookCredentials(webhookCredentials)
            if (statusReportingMode == null) {
                statusReportingMode = CnbStatusReportingMode.BOTH
            }
            automaticBuildBadgeKey = normalizeBuildBadgeKey(automaticBuildBadgeKey)
            normalizedWebUri()
            normalizedApiUri()
            return this
        }

        @Extension
        @Symbol("cnbServer")
        class DescriptorImpl : Descriptor<CnbServer>() {
            override fun getDisplayName(): String = Messages.CnbServer_DisplayName()

            fun doFillCredentialsIdItems(
                @QueryParameter apiUrl: String?,
                @QueryParameter credentialsId: String?,
            ): ListBoxModel {
                if (!Jenkins.get().hasPermission(Jenkins.MANAGE)) {
                    return StandardListBoxModel().includeCurrentValue(credentialsId.orEmpty())
                }
                return StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                        hudson.security.ACL.SYSTEM2,
                        Jenkins.get(),
                        StandardCredentials::class.java,
                        URIRequirementBuilder.fromUri(apiUrl.orEmpty()).build(),
                        CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials::class.java),
                            CredentialsMatchers.instanceOf(
                                org.jenkinsci.plugins.plaincredentials.StringCredentials::class.java,
                            ),
                        ),
                    )
            }

            fun doFillReportingCredentialsIdItems(
                @QueryParameter apiUrl: String?,
                @QueryParameter reportingCredentialsId: String?,
            ): ListBoxModel = apiCredentialItems(apiUrl, reportingCredentialsId)

            fun doFillStatusReportingModeItems(): ListBoxModel =
                ListBoxModel().apply {
                    CnbStatusReportingMode.entries.forEach {
                        add(it.name.lowercase(Locale.ROOT).replace('_', ' '), it.name)
                    }
                }

            @POST
            fun doCheckAutomaticBuildBadgeKey(
                @QueryParameter value: String?,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return try {
                    normalizeBuildBadgeKey(value)
                    FormValidation.ok()
                } catch (exception: IllegalArgumentException) {
                    FormValidation.error(exception.message)
                }
            }

            @POST
            fun doCheckId(
                @QueryParameter value: String?,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                val candidate = value?.trim().orEmpty()
                return if (candidate.matches(ID_PATTERN)) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Use 1-64 letters, digits, dots, underscores, or hyphens")
                }
            }

            @POST
            fun doCheckName(
                @QueryParameter value: String?,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return if (value.isNullOrBlank()) FormValidation.error("Name is required") else FormValidation.ok()
            }

            @POST
            fun doCheckWebUrl(
                @QueryParameter value: String?,
                @QueryParameter allowInsecureHttp: Boolean,
                @QueryParameter allowPrivateNetwork: Boolean,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return checkEndpoint(value, allowInsecureHttp, allowPrivateNetwork)
            }

            @POST
            fun doCheckApiUrl(
                @QueryParameter value: String?,
                @QueryParameter allowInsecureHttp: Boolean,
                @QueryParameter allowPrivateNetwork: Boolean,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return checkEndpoint(value, allowInsecureHttp, allowPrivateNetwork)
            }

            @POST
            fun doValidateConfiguration(
                @QueryParameter apiUrl: String?,
                @QueryParameter allowInsecureHttp: Boolean,
                @QueryParameter allowPrivateNetwork: Boolean,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return checkEndpoint(apiUrl, allowInsecureHttp, allowPrivateNetwork)
            }

            @POST
            fun doTestConnection(
                @QueryParameter webUrl: String?,
                @QueryParameter apiUrl: String?,
                @QueryParameter credentialsId: String?,
                @QueryParameter allowInsecureHttp: Boolean,
                @QueryParameter allowPrivateNetwork: Boolean,
                @QueryParameter connectTimeoutSeconds: Int,
                @QueryParameter requestTimeoutSeconds: Int,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return try {
                    val temporary =
                        CnbServer(
                            "connection-test",
                            "Connection test",
                            webUrl.orEmpty(),
                            apiUrl.orEmpty(),
                        )
                    temporary.setAllowInsecureHttp(allowInsecureHttp)
                    temporary.setAllowPrivateNetwork(allowPrivateNetwork)
                    temporary.setConnectTimeoutSeconds(connectTimeoutSeconds)
                    temporary.setRequestTimeoutSeconds(requestTimeoutSeconds)
                    val token =
                        credentialsId?.takeIf { it.isNotBlank() }?.let {
                            CnbClientFactory.resolveToken(it, temporary.apiUrl, null)
                        }
                    val user = HttpCnbClient(temporary, token).use { it.testConnection() }
                    FormValidation.ok("Authenticated as ${user.username}")
                } catch (e: Exception) {
                    FormValidation.error("CNB connection failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            private fun apiCredentialItems(
                apiUrl: String?,
                currentValue: String?,
            ): ListBoxModel {
                if (!Jenkins.get().hasPermission(Jenkins.MANAGE)) {
                    return StandardListBoxModel().includeCurrentValue(currentValue.orEmpty())
                }
                return StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                        hudson.security.ACL.SYSTEM2,
                        Jenkins.get(),
                        StandardCredentials::class.java,
                        URIRequirementBuilder.fromUri(apiUrl.orEmpty()).build(),
                        CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials::class.java),
                            CredentialsMatchers.instanceOf(
                                org.jenkinsci.plugins.plaincredentials.StringCredentials::class.java,
                            ),
                        ),
                    )
            }

            private fun checkEndpoint(
                value: String?,
                allowInsecureHttp: Boolean,
                allowPrivateNetwork: Boolean,
            ): FormValidation =
                try {
                    CnbEndpointPolicy.validateAndNormalize(
                        value.orEmpty(),
                        allowInsecureHttp,
                        allowPrivateNetwork,
                    )
                    FormValidation.ok()
                } catch (e: Exception) {
                    FormValidation.error(e.message ?: "Endpoint validation failed")
                }
        }

        companion object {
            private const val serialVersionUID = 1L
            private const val MAX_NAME_LENGTH = 128
            private const val MAX_ENDPOINT_LENGTH = 2_048
            private const val MAX_BUILD_BADGE_KEY_LENGTH = 1_024
            private const val DEFAULT_BUILD_BADGE_KEY = "security/tca"
            internal val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

            private fun normalizeId(value: String): String {
                requireNoControlCharacters("CNB server ID", value)
                val normalized = value.trim()
                require(ID_PATTERN.matches(normalized)) {
                    "CNB server ID must use 1-64 letters, digits, dots, underscores, or hyphens"
                }
                return normalized
            }

            private fun normalizeName(
                value: String,
                fallback: String,
            ): String {
                requireNoControlCharacters("CNB server name", value)
                val normalized = value.trim().ifEmpty { fallback }
                require(normalized.length <= MAX_NAME_LENGTH) {
                    "CNB server name must not exceed $MAX_NAME_LENGTH characters"
                }
                return normalized
            }

            private fun normalizeEndpoint(
                field: String,
                value: String,
            ): String {
                requireNoControlCharacters("CNB server $field", value)
                val normalized = value.trim().removeSuffix("/")
                require(normalized.length in 1..MAX_ENDPOINT_LENGTH) {
                    "CNB server $field must contain 1-$MAX_ENDPOINT_LENGTH characters"
                }
                return normalized
            }

            private fun normalizeBuildBadgeKey(value: String?): String {
                val normalized = value?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_BUILD_BADGE_KEY
                require(normalized.length <= MAX_BUILD_BADGE_KEY_LENGTH) {
                    "CNB build status badge key must be at most $MAX_BUILD_BADGE_KEY_LENGTH characters"
                }
                val segments = normalized.split('/')
                require(
                    !normalized.startsWith('/') &&
                        !normalized.endsWith('/') &&
                        segments.none { it.isEmpty() || it == "." || it == ".." } &&
                        normalized.none { it == '\\' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f },
                ) { "CNB build status badge key must be a safe relative path" }
                return normalized
            }

            private fun requireNoControlCharacters(
                field: String,
                value: String,
            ) {
                require(value.none { it.isISOControl() }) { "$field must not contain control characters" }
            }

            fun defaultServer() =
                CnbServer(
                    id = "cnb-cool",
                    name = "CNB Cool",
                    webUrl = "https://cnb.cool",
                    apiUrl = "https://api.cnb.cool",
                )
        }
    }
