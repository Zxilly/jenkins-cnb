package dev.zxilly.jenkins.cnb.scm

import jenkins.scm.api.SCMNavigator
import jenkins.scm.api.SCMSourceObserver
import jenkins.scm.api.trait.SCMNavigatorContext
import jenkins.scm.api.trait.SCMNavigatorRequest
import jenkins.scm.api.trait.SCMSourceBuilder

/** Scan settings for a CNB namespace. */
class CnbSCMNavigatorContext : SCMNavigatorContext<CnbSCMNavigatorContext, CnbSCMNavigatorRequest>() {
    var includeDescendants: Boolean = true
    var includeArchivedRepositories: Boolean = false

    override fun newRequest(
        navigator: SCMNavigator,
        observer: SCMSourceObserver,
    ): CnbSCMNavigatorRequest = CnbSCMNavigatorRequest(navigator, this, observer)
}

/** Immutable namespace scan request. */
class CnbSCMNavigatorRequest(
    navigator: SCMNavigator,
    context: CnbSCMNavigatorContext,
    observer: SCMSourceObserver,
) : SCMNavigatorRequest(navigator, context, observer) {
    val includeDescendants: Boolean = context.includeDescendants
    val includeArchivedRepositories: Boolean = context.includeArchivedRepositories
}

/** Applies navigator and source traits before proposing a repository. */
class CnbSCMSourceBuilder(
    private val sourceId: String,
    private val serverId: String,
    private val credentialsId: String?,
    private val checkoutCredentialsId: String?,
    private val repositoryPath: String,
    projectName: String,
) : SCMSourceBuilder<CnbSCMSourceBuilder, CnbSCMSource>(CnbSCMSource::class.java, projectName) {
    override fun build(): CnbSCMSource =
        CnbSCMSource(serverId, repositoryPath).also { source ->
            source.setId(sourceId)
            source.setApiCredentialsId(credentialsId)
            source.setCheckoutCredentialsId(checkoutCredentialsId)
            source.setTraits(traits())
        }
}
