package dev.zxilly.jenkins.cnb.scm

import hudson.plugins.git.GitSCM
import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.plugins.git.GitSCMBuilder
import jenkins.plugins.git.MergeWithGitSCMExtension
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.eclipse.jgit.lib.Repository

/** Builds HTTPS-only Git checkouts for CNB branches, tags, and pull requests. */
class CnbSCMBuilder(
    private val source: CnbSCMSource,
    head: SCMHead,
    revision: SCMRevision?,
) : GitSCMBuilder<CnbSCMBuilder>(
        head,
        revision,
        source.targetCloneUrl(),
        source.checkoutCredentialsIdForGit(),
    ) {
    private val trustedTargetRevision =
        head is CnbPullRequestSCMHead && revision is CnbBranchSCMRevision

    init {
        withoutRefSpecs()
        when (head) {
            is CnbPullRequestSCMHead -> {
                validateGitRef("refs/heads/${head.target.name}")
                if (trustedTargetRevision) {
                    require(revision!!.head == head.target) {
                        "Trusted CNB pull request revision must belong to the target branch"
                    }
                    withRemote(source.targetCloneUrl())
                    withRefSpec(
                        "+refs/heads/${head.target.name}:refs/remotes/@{remote}/${head.target.name}",
                    )
                    withBrowser(CnbRepositoryBrowser(source.targetWebUrl()))
                } else {
                    validateGitRef("refs/heads/${head.sourceBranch}")
                    withRemote(source.cloneUrlFor(head.sourceRepository))
                    withRefSpec(
                        "+refs/heads/${head.sourceBranch}:refs/remotes/@{remote}/${head.name}",
                    )
                    withBrowser(CnbRepositoryBrowser(source.webUrlFor(head.sourceRepository)))
                    if (head.checkoutStrategy == ChangeRequestCheckoutStrategy.MERGE) {
                        withAdditionalRemote(
                            UPSTREAM_REMOTE,
                            source.targetCloneUrl(),
                            "+refs/heads/${head.target.name}:refs/remotes/$UPSTREAM_REMOTE/${head.target.name}",
                        )
                    }
                }
            }

            is CnbTagSCMHead -> {
                validateGitRef("refs/tags/${head.name}")
                withRefSpec("+refs/tags/${head.name}:refs/tags/${head.name}")
                withBrowser(CnbRepositoryBrowser(source.targetWebUrl()))
            }

            else -> {
                validateGitRef("refs/heads/${head.name}")
                withRefSpec("+refs/heads/${head.name}:refs/remotes/@{remote}/${head.name}")
                withBrowser(CnbRepositoryBrowser(source.targetWebUrl()))
            }
        }
    }

    override fun build(): GitSCM {
        val originalHead = head()
        val originalRevision = revision()
        try {
            val pullHead = originalHead as? CnbPullRequestSCMHead
            val pullRevision = originalRevision as? CnbPullRequestSCMRevision
            if (pullHead != null && originalRevision is CnbBranchSCMRevision) {
                // Jenkins asks for this shape while loading a trusted Jenkinsfile for an untrusted fork.
                withHead(originalRevision.head)
            } else if (pullHead != null) {
                if (pullRevision != null) {
                    withRevision(AbstractGitSCMSource.SCMRevisionImpl(pullHead, pullRevision.headHash))
                }
                if (pullHead.checkoutStrategy == ChangeRequestCheckoutStrategy.MERGE) {
                    withExtension(
                        MergeWithGitSCMExtension(
                            "remotes/$UPSTREAM_REMOTE/${pullHead.target.name}",
                            pullRevision?.baseHash,
                        ),
                    )
                }
            }
            return super.build()
        } finally {
            withHead(originalHead)
            withRevision(originalRevision)
        }
    }

    companion object {
        private const val UPSTREAM_REMOTE = "upstream"

        private fun validateGitRef(ref: String) {
            require(Repository.isValidRefName(ref)) { "CNB returned invalid Git ref '$ref'" }
        }
    }
}
