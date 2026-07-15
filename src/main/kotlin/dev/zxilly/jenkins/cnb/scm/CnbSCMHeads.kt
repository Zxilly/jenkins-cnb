package dev.zxilly.jenkins.cnb.scm

import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.plugins.git.GitTagSCMHead
import jenkins.plugins.git.GitTagSCMRevision
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.mixin.ChangeRequestSCMHead2
import jenkins.scm.api.mixin.ChangeRequestSCMRevision
import jenkins.scm.api.mixin.TagSCMHead
import org.kohsuke.stapler.export.Exported

/** A branch advertised by CNB. */
class CnbBranchSCMHead(
    name: String,
) : SCMHead(name) {
    override fun getPronoun(): String = "Branch"
}

/** An immutable branch revision. */
class CnbBranchSCMRevision(
    head: CnbBranchSCMHead,
    hash: String,
) : AbstractGitSCMSource.SCMRevisionImpl(head, hash) {
    init {
        require(hash.isNotBlank()) { "CNB branch revision hash must not be blank" }
    }
}

/** A tag advertised by CNB. */
class CnbTagSCMHead(
    name: String,
    timestamp: Long,
) : GitTagSCMHead(name, timestamp),
    TagSCMHead {
    override fun getPronoun(): String = "Tag"
}

/** An immutable tag revision. */
class CnbTagSCMRevision(
    head: CnbTagSCMHead,
    hash: String,
) : GitTagSCMRevision(head, hash) {
    init {
        require(hash.isNotBlank()) { "CNB tag revision hash must not be blank" }
    }
}

/**
 * A pull request head.
 *
 * The checkout strategy is encoded in the head name and retained explicitly so that Jenkins can
 * safely keep HEAD and MERGE jobs side by side when both strategies are enabled.
 */
class CnbPullRequestSCMHead(
    name: String,
    val number: String,
    val targetBranch: CnbBranchSCMHead,
    private val strategy: ChangeRequestCheckoutStrategy,
    private val origin: SCMHeadOrigin,
    val sourceRepository: String,
    val sourceBranch: String,
    val author: String,
    val title: String,
) : SCMHead(name),
    ChangeRequestSCMHead2 {
    init {
        require(number.isNotBlank()) { "CNB pull request number must not be blank" }
        require(sourceRepository.isNotBlank()) { "CNB pull request source repository must not be blank" }
        require(sourceBranch.isNotBlank()) { "CNB pull request source branch must not be blank" }
    }

    override fun getPronoun(): String = "Pull Request"

    override fun getId(): String = number

    override fun getTarget(): CnbBranchSCMHead = targetBranch

    override fun getOriginName(): String = sourceBranch

    override fun getOrigin(): SCMHeadOrigin = origin

    override fun getCheckoutStrategy(): ChangeRequestCheckoutStrategy = strategy

    val isMerge: Boolean
        get() = strategy == ChangeRequestCheckoutStrategy.MERGE
}

/**
 * A pull request revision, retaining both sides of the change request.
 *
 * [mergeHash] is the server-computed merge revision when CNB exposes one. Jenkins performs a local,
 * deterministic merge when it is absent, using [baseHash] and [headHash].
 */
class CnbPullRequestSCMRevision(
    head: CnbPullRequestSCMHead,
    val baseHash: String,
    @get:Exported val headHash: String,
    val mergeHash: String? = null,
) : ChangeRequestSCMRevision<CnbPullRequestSCMHead>(
        head,
        CnbBranchSCMRevision(head.targetBranch, baseHash),
    ) {
    init {
        require(baseHash.isNotBlank()) { "CNB pull request base hash must not be blank" }
        require(headHash.isNotBlank()) { "CNB pull request head hash must not be blank" }
    }

    override fun equivalent(revision: ChangeRequestSCMRevision<*>): Boolean =
        revision is CnbPullRequestSCMRevision &&
            head == revision.head &&
            headHash == revision.headHash

    override fun _hashCode(): Int = 31 * head.hashCode() + headHash.hashCode()

    override fun toString(): String =
        if ((head as CnbPullRequestSCMHead).isMerge) {
            "$headHash+$baseHash (${mergeHash ?: "LOCAL_MERGE"})"
        } else {
            headHash
        }
}
