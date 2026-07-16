package dev.zxilly.jenkins.cnb.scm

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.common.StandardCredentials
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.trigger.CnbSCMHeadEvent
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.Extension
import hudson.Util
import hudson.model.Action
import hudson.model.Item
import hudson.model.TaskListener
import hudson.security.ACL
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.plugins.git.traits.GitBrowserSCMSourceTrait
import jenkins.scm.api.SCMHeadEvent
import jenkins.scm.api.SCMNavigator
import jenkins.scm.api.SCMNavigatorDescriptor
import jenkins.scm.api.SCMNavigatorEvent
import jenkins.scm.api.SCMNavigatorOwner
import jenkins.scm.api.SCMSourceObserver
import jenkins.scm.api.metadata.ObjectMetadataAction
import jenkins.scm.api.trait.SCMNavigatorRequest
import jenkins.scm.api.trait.SCMNavigatorTrait
import jenkins.scm.api.trait.SCMSourceTrait
import jenkins.scm.api.trait.SCMTrait
import jenkins.scm.api.trait.SCMTraitDescriptor
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** Discovers repositories below a CNB organization, group, or user namespace. */
open class CnbSCMNavigator
    @DataBoundConstructor
    constructor(
        serverId: String,
        namespace: String,
    ) : SCMNavigator() {
        val serverId: String = serverId.trim()
        val namespace: String = namespace.trim().trim('/')

        private var configuredCredentialsId: String? = null
        private var configuredCheckoutCredentialsId: String? = null
        private var includeDescendants: Boolean = true
        private var includeArchivedRepositories: Boolean = false
        private var discoverAllRepositories: Boolean = false
        private var configuredTraits: List<SCMTrait<out SCMTrait<*>>> = defaultTraits()

        init {
            require(this.serverId.isNotBlank()) { "CNB server ID must not be blank" }
            require(
                this.namespace.isBlank() ||
                    this.namespace.split('/').none { it.isBlank() || it == "." || it == ".." },
            ) {
                "CNB namespace must contain only non-relative path segments"
            }
        }

        val credentialsId: String?
            get() = configuredCredentialsId

        val checkoutCredentialsId: String?
            get() = configuredCheckoutCredentialsId

        fun isIncludeDescendants(): Boolean = includeDescendants

        fun isIncludeArchivedRepositories(): Boolean = includeArchivedRepositories

        fun isDiscoverAllRepositories(): Boolean = discoverAllRepositories

        @DataBoundSetter
        fun setCredentialsId(value: String?) {
            configuredCredentialsId = Util.fixEmptyAndTrim(value)
        }

        @DataBoundSetter
        fun setCheckoutCredentialsId(value: String?) {
            configuredCheckoutCredentialsId = Util.fixEmptyAndTrim(value)
        }

        @DataBoundSetter
        fun setIncludeDescendants(value: Boolean) {
            includeDescendants = value
        }

        @DataBoundSetter
        fun setIncludeArchivedRepositories(value: Boolean) {
            includeArchivedRepositories = value
        }

        @DataBoundSetter
        fun setDiscoverAllRepositories(value: Boolean) {
            discoverAllRepositories = value
        }

        override fun getTraits(): List<SCMTrait<out SCMTrait<*>>> = configuredTraits.toList()

        @DataBoundSetter
        override fun setTraits(value: List<SCMTrait<out SCMTrait<*>>>?) {
            configuredTraits = ArrayList(value.orEmpty())
        }

        override fun id(): String = "$serverId::${if (discoverAllRepositories) "*" else namespace}"

        @SuppressFBWarnings(
            value = ["RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"],
            justification = "XStream can restore a missing legacy traits field as JVM null despite Kotlin's declaration.",
        )
        private fun readResolve(): Any {
            val restoredTraits: List<SCMTrait<out SCMTrait<*>>>? = configuredTraits
            configuredTraits = ArrayList(restoredTraits ?: defaultTraits())
            return this
        }

        override fun visitSources(observer: SCMSourceObserver) {
            newRequest(observer).use { request ->
                client(observer.context).use { client ->
                    val repositories =
                        if (discoverAllRepositories) {
                            client.listUserRepositories()
                        } else {
                            require(namespace.isNotBlank()) { "CNB namespace is required unless all accessible repositories are enabled" }
                            client.listRepositories(namespace, request.includeDescendants)
                        }
                    for (repository in repositories) {
                        if (proposeRepository(request, observer, repository)) return
                    }
                }
            }
        }

        override fun visitSources(
            observer: SCMSourceObserver,
            event: SCMHeadEvent<*>,
        ) {
            val cnbEvent = event as? CnbSCMHeadEvent
            if (cnbEvent == null) {
                super.visitSources(observer, event)
                return
            }
            if (!cnbEvent.isMatch(this)) return

            newRequest(observer).use { request ->
                client(observer.context).use { client ->
                    val repository =
                        try {
                            client.getRepository(cnbEvent.repositoryPath)
                        } catch (failure: CnbApiException) {
                            if (failure.statusCode != 404) throw failure
                            observer.listener.logger.printf(
                                "Ignoring CNB event for missing repository %s%n",
                                cnbEvent.repositoryPath,
                            )
                            return
                        }
                    if (repository.path.trim('/') != cnbEvent.repositoryPath) {
                        throw IOException(
                            "CNB API returned repository '${repository.path}' for event repository '${cnbEvent.repositoryPath}'",
                        )
                    }
                    proposeRepository(request, observer, repository)
                }
            }
        }

        override fun retrieveActions(
            owner: SCMNavigatorOwner,
            event: SCMNavigatorEvent<*>?,
            listener: TaskListener,
        ): List<Action> {
            val server = CnbGlobalConfiguration.get().findServer(serverId)
            if (discoverAllRepositories) {
                return listOf(ObjectMetadataAction("Accessible repositories", null, server.webUrl))
            }
            val namespaceUrl = "${server.webUrl.trimEnd('/')}/${namespace.trim('/')}"
            return listOf(ObjectMetadataAction(namespace.substringAfterLast('/'), null, namespaceUrl))
        }

        private fun projectName(repositoryPath: String): String = projectNameFor(repositoryPath)

        protected open fun client(context: Item?): CnbClient = CnbClientFactory.create(serverId, credentialsId, context)

        private fun newRequest(observer: SCMSourceObserver): CnbSCMNavigatorRequest =
            CnbSCMNavigatorContext()
                .apply {
                    includeDescendants = this@CnbSCMNavigator.includeDescendants
                    includeArchivedRepositories = this@CnbSCMNavigator.includeArchivedRepositories
                }.withTraits(traits)
                .newRequest(this, observer)

        private fun proposeRepository(
            request: CnbSCMNavigatorRequest,
            observer: SCMSourceObserver,
            repository: CnbRepository,
        ): Boolean {
            if (!request.includeArchivedRepositories && repository.archived) {
                observer.listener.logger.printf("Ignoring archived CNB repository %s%n", repository.path)
                return false
            }
            if (!repository.cloneable) {
                observer.listener.logger.printf("Ignoring non-cloneable CNB repository %s%n", repository.path)
                return false
            }
            val projectName = projectName(repository.path)
            return request.process(
                projectName,
                SCMNavigatorRequest.SourceLambda { name ->
                    CnbSCMSourceBuilder(
                        "$id::${repository.path}",
                        serverId,
                        credentialsId,
                        checkoutCredentialsId,
                        repository.path,
                        name,
                    ).withRequest(request).build()
                },
                null,
                SCMNavigatorRequest.Witness { name, matches ->
                    observer.listener.logger.printf(
                        "%s CNB repository %s%n",
                        if (matches) "Proposing" else "Ignoring",
                        name,
                    )
                },
            )
        }

        @Extension
        @Symbol("cnbOrganization")
        @Suppress("OVERRIDE_DEPRECATION")
        class DescriptorImpl : SCMNavigatorDescriptor() {
            override fun getDisplayName(): String = "CNB namespace"

            override fun getPronoun(): String = "Namespace"

            override fun getDescription(): String = "Scans a CNB namespace for repositories."

            override fun getIconClassName(): String = "symbol-organization plugin-ionicons-api"

            override fun newInstance(name: String?): SCMNavigator {
                val server =
                    CnbGlobalConfiguration
                        .get()
                        .getServers()
                        .firstOrNull()
                        ?.id ?: "cnb-cool"
                return CnbSCMNavigator(server, name.orEmpty()).also { it.setTraits(defaultTraits()) }
            }

            override fun getTraitsDefaults(): List<SCMTrait<out SCMTrait<*>>> = defaultTraits()

            val traitDescriptors: List<SCMTraitDescriptor<*>>
                get() {
                    val result = ArrayList<SCMTraitDescriptor<*>>()
                    for (
                    descriptor in
                    SCMNavigatorTrait._for(
                        this@DescriptorImpl,
                        CnbSCMNavigatorContext::class.java,
                        CnbSCMSourceBuilder::class.java,
                    )
                    ) {
                        addTraitDescriptor(result, descriptor)
                    }
                    val sourceDescriptor =
                        jenkins.model.Jenkins
                            .get()
                            .getDescriptorByType(CnbSCMSource.DescriptorImpl::class.java)
                    for (descriptor in SCMSourceTrait._for(sourceDescriptor, CnbSCMSourceContext::class.java, null)) {
                        addTraitDescriptor(result, descriptor)
                    }
                    for (descriptor in SCMSourceTrait._for(sourceDescriptor, null, CnbSCMBuilder::class.java)) {
                        addTraitDescriptor(result, descriptor)
                    }
                    return result
                }

            val traitsDescriptors: List<SCMTraitDescriptor<*>>
                get() = traitDescriptors

            fun doFillServerIdItems(
                @QueryParameter serverId: String?,
            ): ListBoxModel =
                ListBoxModel().apply {
                    CnbGlobalConfiguration.get().getServers().forEach { add(it.name, it.id) }
                    if (!serverId.isNullOrBlank() && none { it.value == serverId }) {
                        add(serverId, serverId)
                    }
                }

            fun doFillCredentialsIdItems(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter serverId: String?,
                @QueryParameter credentialsId: String?,
            ): ListBoxModel {
                val result = StandardListBoxModel()
                if (context == null) {
                    if (!jenkins.model.Jenkins
                            .get()
                            .hasPermission(jenkins.model.Jenkins.MANAGE)
                    ) {
                        return result.includeCurrentValue(credentialsId.orEmpty())
                    }
                } else if (!context.hasPermission(Item.CONFIGURE)) {
                    return result.includeCurrentValue(credentialsId.orEmpty())
                }
                val server = serverId?.let { id -> CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == id } }
                return result
                    .includeEmptyValue()
                    .includeMatchingAs(
                        ACL.SYSTEM2,
                        context,
                        StandardCredentials::class.java,
                        server?.apiUrl?.let { URIRequirementBuilder.fromUri(it).build() }.orEmpty(),
                        CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StringCredentials::class.java),
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials::class.java),
                        ),
                    )
            }

            fun doFillCheckoutCredentialsIdItems(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter serverId: String?,
                @QueryParameter checkoutCredentialsId: String?,
            ): ListBoxModel {
                if (context == null) {
                    if (!jenkins.model.Jenkins
                            .get()
                            .hasPermission(jenkins.model.Jenkins.MANAGE)
                    ) {
                        return CnbScmCredentials.currentCheckoutCredential(checkoutCredentialsId)
                    }
                } else if (!context.hasPermission(Item.CONFIGURE)) {
                    return CnbScmCredentials.currentCheckoutCredential(checkoutCredentialsId)
                }
                return CnbScmCredentials.checkoutCredentialItems(context, checkoutCredentialsId, serverId)
            }

            @POST
            fun doCheckCheckoutCredentialsId(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter serverId: String?,
                @QueryParameter credentialsId: String?,
                @QueryParameter checkoutCredentialsId: String?,
            ): FormValidation {
                checkConfigurePermission(context)
                return CnbScmCredentials.checkCheckoutCredentials(
                    serverId,
                    credentialsId,
                    checkoutCredentialsId,
                    context,
                )
            }

            @POST
            fun doCheckServerId(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter value: String?,
            ): FormValidation {
                checkConfigurePermission(context)
                return if (value.isNullOrBlank() || CnbGlobalConfiguration.get().getServers().none { it.id == value }) {
                    FormValidation.error("Select a configured CNB server")
                } else {
                    FormValidation.ok()
                }
            }

            @POST
            fun doCheckNamespace(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter value: String?,
                @QueryParameter discoverAllRepositories: Boolean,
            ): FormValidation {
                checkConfigurePermission(context)
                return if (discoverAllRepositories) {
                    FormValidation.ok()
                } else if (value.isNullOrBlank() || !value.trim().matches(Regex("[^/\\s]+(?:/[^/\\s]+)*"))) {
                    FormValidation.error("Enter a CNB user, group, or organization namespace")
                } else {
                    FormValidation.ok()
                }
            }

            private fun checkConfigurePermission(context: jenkins.scm.api.SCMSourceOwner?) {
                if (context == null) {
                    jenkins.model.Jenkins
                        .get()
                        .checkPermission(jenkins.model.Jenkins.MANAGE)
                } else {
                    context.checkPermission(Item.CONFIGURE)
                }
            }

            private fun addTraitDescriptor(
                descriptors: MutableList<SCMTraitDescriptor<*>>,
                descriptor: SCMTraitDescriptor<*>,
            ) {
                if (descriptor !is GitBrowserSCMSourceTrait.DescriptorImpl && descriptor !in descriptors) {
                    descriptors.add(descriptor)
                }
            }
        }

        companion object {
            private const val serialVersionUID = 1L

            internal fun projectNameFor(repositoryPath: String): String {
                val canonical = repositoryPath.trim().trim('/')
                val digest =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                val suffix = HexFormat.of().formatHex(digest, 0, 10)
                return "${canonical.substringAfterLast('/')}-$suffix"
            }

            private fun defaultTraits(): List<SCMTrait<out SCMTrait<*>>> =
                arrayListOf(
                    CnbBranchDiscoveryTrait(3),
                    CnbOriginPullRequestDiscoveryTrait(1),
                    CnbForkPullRequestDiscoveryTrait(1, TrustNobody()),
                )
        }
    }
