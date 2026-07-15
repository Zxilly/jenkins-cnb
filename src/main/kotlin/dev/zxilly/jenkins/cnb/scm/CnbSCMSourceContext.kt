package dev.zxilly.jenkins.cnb.scm

import hudson.model.TaskListener
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMSourceCriteria
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.trait.SCMSourceContext
import java.util.Collections
import java.util.EnumSet

/** Mutable scan context assembled by CNB discovery traits. */
class CnbSCMSourceContext(
    criteria: SCMSourceCriteria?,
    observer: SCMHeadObserver,
) : SCMSourceContext<CnbSCMSourceContext, CnbSCMSourceRequest>(criteria, observer) {
    var wantsBranches: Boolean = false
        private set
    var wantsTags: Boolean = false
        private set
    var wantsOriginPullRequests: Boolean = false
        private set
    var wantsForkPullRequests: Boolean = false
        private set

    private val originStrategies = EnumSet.noneOf(ChangeRequestCheckoutStrategy::class.java)
    private val forkStrategies = EnumSet.noneOf(ChangeRequestCheckoutStrategy::class.java)

    val wantsPullRequests: Boolean
        get() = wantsOriginPullRequests || wantsForkPullRequests

    fun wantBranches(include: Boolean): CnbSCMSourceContext =
        apply {
            wantsBranches = wantsBranches || include
        }

    fun wantTags(include: Boolean): CnbSCMSourceContext =
        apply {
            wantsTags = wantsTags || include
        }

    fun wantOriginPullRequests(include: Boolean): CnbSCMSourceContext =
        apply {
            wantsOriginPullRequests = wantsOriginPullRequests || include
        }

    fun wantForkPullRequests(include: Boolean): CnbSCMSourceContext =
        apply {
            wantsForkPullRequests = wantsForkPullRequests || include
        }

    fun withOriginPullRequestStrategies(strategies: Set<ChangeRequestCheckoutStrategy>): CnbSCMSourceContext =
        apply {
            originStrategies.addAll(strategies)
        }

    fun withForkPullRequestStrategies(strategies: Set<ChangeRequestCheckoutStrategy>): CnbSCMSourceContext =
        apply {
            forkStrategies.addAll(strategies)
        }

    fun originPullRequestStrategies(): Set<ChangeRequestCheckoutStrategy> = Collections.unmodifiableSet(originStrategies)

    fun forkPullRequestStrategies(): Set<ChangeRequestCheckoutStrategy> = Collections.unmodifiableSet(forkStrategies)

    override fun newRequest(
        source: SCMSource,
        listener: TaskListener?,
    ): CnbSCMSourceRequest = CnbSCMSourceRequest(source, this, listener)
}
