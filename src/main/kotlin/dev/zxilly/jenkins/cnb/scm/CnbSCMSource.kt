package dev.zxilly.jenkins.cnb.scm

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.common.StandardCredentials
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import hudson.Extension
import hudson.Util
import hudson.model.Action
import hudson.model.Item
import hudson.model.TaskListener
import hudson.scm.SCM
import hudson.security.ACL
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.plugins.git.traits.GitBrowserSCMSourceTrait
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadCategory
import jenkins.scm.api.SCMHeadEvent
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.SCMSourceCriteria
import jenkins.scm.api.SCMSourceDescriptor
import jenkins.scm.api.SCMSourceEvent
import jenkins.scm.api.metadata.ContributorMetadataAction
import jenkins.scm.api.metadata.ObjectMetadataAction
import jenkins.scm.api.metadata.PrimaryInstanceMetadataAction
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.trait.SCMSourceRequest
import jenkins.scm.api.trait.SCMSourceTrait
import jenkins.scm.api.trait.SCMSourceTraitDescriptor
import jenkins.scm.impl.ChangeRequestSCMHeadCategory
import jenkins.scm.impl.TagSCMHeadCategory
import jenkins.scm.impl.UncategorizedSCMHeadCategory
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A multibranch source for one CNB repository. */
open class CnbSCMSource
    @DataBoundConstructor
    constructor(
        serverId: String,
        repositoryPath: String,
    ) : AbstractGitSCMSource() {
        val serverId: String = serverId.trim()
        val repositoryPath: String = repositoryPath.trim().trim('/')

        private var configuredApiCredentialsId: String? = null
        private var configuredCheckoutCredentialsId: String? = null
        private var configuredTraits: List<SCMSourceTrait> = defaultTraits()

        @Transient
        @Volatile
        private var resolvedRepository: CnbRepository? = null

        init {
            require(this.serverId.isNotBlank()) { "CNB server ID must not be blank" }
            require(
                this.repositoryPath.contains('/') &&
                    this.repositoryPath.split('/').none { it.isBlank() || it == "." || it == ".." },
            ) { "CNB repository path must contain a namespace and repository name" }
        }

        /**
         * The Git transport credential required by [AbstractGitSCMSource].
         *
         * CNB API authentication is deliberately exposed as [getApiCredentialsId] instead.
         */
        override fun getCredentialsId(): String? = configuredCheckoutCredentialsId

        @DataBoundSetter
        fun setCredentialsId(value: String?) {
            configuredCheckoutCredentialsId = Util.fixEmptyAndTrim(value)
        }

        fun getApiCredentialsId(): String? = configuredApiCredentialsId

        @DataBoundSetter
        fun setApiCredentialsId(value: String?) {
            configuredApiCredentialsId = Util.fixEmptyAndTrim(value)
        }

        fun getCheckoutCredentialsId(): String? = configuredCheckoutCredentialsId

        @DataBoundSetter
        fun setCheckoutCredentialsId(value: String?) {
            configuredCheckoutCredentialsId = Util.fixEmptyAndTrim(value)
        }

        internal fun checkoutCredentialsIdForGit(): String? =
            CnbScmCredentials.checkoutCredentialsId(
                serverId,
                getApiCredentialsId(),
                getCheckoutCredentialsId(),
                owner,
            )

        override fun getTraits(): List<SCMSourceTrait> = configuredTraits.toList()

        @DataBoundSetter
        override fun setTraits(value: List<SCMSourceTrait>?) {
            configuredTraits = ArrayList(value.orEmpty())
        }

        override fun getRemote(): String = targetCloneUrl()

        internal fun targetCloneUrl(): String = resolvedRepository?.let { secureCloneUrl(it) } ?: cloneUrlFor(repositoryPath)

        internal fun targetWebUrl(): String = resolvedRepository?.webUrl ?: webUrlFor(repositoryPath)

        internal fun cloneUrlFor(path: String): String {
            val target = resolvedRepository
            if (target != null && target.path == path) {
                return secureCloneUrl(target)
            }
            return requireHttpsCloneUrl(derivedCloneUrl(path))
        }

        internal fun webUrlFor(path: String): String {
            val target = resolvedRepository
            if (target != null && target.path == path) {
                return target.webUrl
            }
            return "${CnbGlobalConfiguration.get().findServer(serverId).webUrl.trimEnd('/')}/${path.trim('/')}"
        }

        override fun retrieve(
            head: SCMHead,
            listener: TaskListener,
        ): SCMRevision? =
            client().use { client ->
                val repository = remember(client.getRepository(repositoryPath))
                when (head) {
                    is CnbBranchSCMHead -> {
                        val branch = client.getBranch(repository.path, head.name)
                        CnbBranchSCMRevision(head, branch.sha)
                    }

                    is CnbTagSCMHead -> {
                        val tag = client.listTags(repository.path).firstOrNull { it.name == head.name }
                        tag?.let { CnbTagSCMRevision(head, it.sha) }
                    }

                    is CnbPullRequestSCMHead -> {
                        CnbPullRequestIdentity.fetchOpen(client, repository.path, head)?.let { pullRequest ->
                            val base = currentBase(client, pullRequest)
                            CnbPullRequestSCMRevision(head, base, pullRequest.sourceSha, pullRequest.mergeSha)
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        /** Keeps named revision lookup on the CNB API and preserves CNB head/revision types. */
        protected override fun retrieve(
            thingName: String,
            listener: TaskListener,
            context: Item?,
        ): SCMRevision? =
            withClientContext(context) {
                val observer = SCMHeadObserver.named(thingName)
                retrieve(null, observer, null, listener)
                observer.result()
            }

        /** Lists the same names exposed by normal CNB discovery rather than raw Git references. */
        protected override fun retrieveRevisions(
            listener: TaskListener,
            context: Item?,
        ): Set<String> =
            withClientContext(context) {
                retrieve(listener).mapTo(sortedSetOf()) { it.name }
            }

        override fun retrieve(
            criteria: SCMSourceCriteria?,
            observer: SCMHeadObserver,
            event: SCMHeadEvent<*>?,
            listener: TaskListener,
        ) {
            client().use { client ->
                val repository = remember(client.getRepository(repositoryPath))
                val context = CnbSCMSourceContext(criteria, observer).withTraits(traits)
                context.newRequest(this, listener).use { request ->
                    if (request.fetchBranches) {
                        request.branches = branchesFor(client, repository.path, request.requestedBranchNames)
                    }
                    if (request.fetchOriginPullRequests || request.fetchForkPullRequests) {
                        request.pullRequests = pullRequestsFor(client, repository.path, request.requestedPullRequestNumbers)
                    }
                    if (request.fetchTags) {
                        val requested = request.requestedTagNames
                        val allTags = client.listTags(repository.path)
                        if (requested == null) {
                            request.tags = allTags
                        } else {
                            val selectedTags = ArrayList<CnbTag>(allTags.size)
                            for (tag in allTags) {
                                if (tag.name in requested) {
                                    selectedTags.add(tag)
                                }
                            }
                            request.tags = selectedTags
                        }
                    }

                    if (request.fetchBranches) {
                        for (branch in request.branches) {
                            val head = CnbBranchSCMHead(branch.name)
                            val revision = CnbBranchSCMRevision(head, branch.sha)
                            if (request.process(
                                    head,
                                    revision,
                                    SCMSourceRequest.ProbeLambda<CnbBranchSCMHead, CnbBranchSCMRevision> { _, _ ->
                                        CnbSCMProbe.shared(client, repository.path, branch.sha, head.name, revision)
                                    },
                                    scanWitness<CnbBranchSCMHead, CnbBranchSCMRevision>(listener, "branch"),
                                )
                            ) {
                                return
                            }
                        }
                    }

                    if ((request.fetchOriginPullRequests || request.fetchForkPullRequests) && !request.isComplete) {
                        val branchesByName = request.branches.associateBy { it.name }
                        for (pullRequest in request.pullRequests) {
                            val fork = pullRequest.fromFork || pullRequest.sourceRepo != pullRequest.targetRepo
                            val strategies = request.strategiesFor(fork)
                            if (strategies.isEmpty()) {
                                continue
                            }
                            val base = branchesByName[pullRequest.targetBranch]?.sha ?: currentBase(client, pullRequest)
                            for (strategy in strategies) {
                                val head = pullRequestHead(pullRequest, strategy, strategies.size > 1)
                                val revision =
                                    CnbPullRequestSCMRevision(
                                        head,
                                        base,
                                        pullRequest.sourceSha,
                                        pullRequest.mergeSha,
                                    )
                                if (request.process(
                                        head,
                                        revision,
                                        SCMSourceRequest.ProbeLambda<CnbPullRequestSCMHead, CnbPullRequestSCMRevision> { h, r ->
                                            val resolved = requireNotNull(r) { "CNB pull request revision was not resolved" }
                                            val trusted = request.isTrusted(h)
                                            if (!trusted) {
                                                listener.logger.printf(
                                                    "Pull request %s is untrusted; probing target %s at %s%n",
                                                    h.id,
                                                    h.target.name,
                                                    resolved.baseHash,
                                                )
                                            }
                                            pullRequestCriteriaProbe(
                                                client,
                                                repository.path,
                                                h,
                                                resolved,
                                                trusted,
                                                ownsClient = false,
                                            )
                                        },
                                        scanWitness<CnbPullRequestSCMHead, CnbPullRequestSCMRevision>(
                                            listener,
                                            "pull request",
                                        ),
                                    )
                                ) {
                                    return
                                }
                            }
                        }
                    }

                    if (request.fetchTags && !request.isComplete) {
                        for (tag in request.tags) {
                            val head = CnbTagSCMHead(tag.name, tag.timestamp)
                            val revision = CnbTagSCMRevision(head, tag.sha)
                            if (request.process(
                                    head,
                                    revision,
                                    SCMSourceRequest.ProbeLambda<CnbTagSCMHead, CnbTagSCMRevision> { _, _ ->
                                        CnbSCMProbe.shared(
                                            client,
                                            repository.path,
                                            tag.sha,
                                            head.name,
                                            revision,
                                            tag.timestamp,
                                        )
                                    },
                                    scanWitness<CnbTagSCMHead, CnbTagSCMRevision>(listener, "tag"),
                                )
                            ) {
                                return
                            }
                        }
                    }
                }
            }
        }

        override fun build(
            head: SCMHead,
            revision: SCMRevision?,
        ): SCM = CnbSCMBuilder(this, head, revision).withTraits(traits).build()

        override fun getTrustedRevision(
            revision: SCMRevision,
            listener: TaskListener,
        ): SCMRevision {
            val pullRequestRevision = revision as? CnbPullRequestSCMRevision ?: return revision
            val head = pullRequestRevision.head as CnbPullRequestSCMHead
            if (isPullRequestTrusted(head, listener)) {
                return revision
            }
            listener.logger.printf(
                "Loading trusted files from target branch %s at %s rather than pull request %s at %s%n",
                head.target.name,
                pullRequestRevision.baseHash,
                head.id,
                pullRequestRevision.headHash,
            )
            return CnbBranchSCMRevision(head.target, pullRequestRevision.baseHash)
        }

        override fun createProbe(
            head: SCMHead,
            revision: SCMRevision?,
        ): CnbSCMProbe {
            val client = client()
            return try {
                val repository = remember(client.getRepository(repositoryPath))
                when {
                    head is CnbPullRequestSCMHead && revision is CnbPullRequestSCMRevision -> {
                        if (revision.head != head) {
                            throw IOException("CNB revision does not belong to pull request ${head.number}")
                        }
                        pullRequestCriteriaProbe(
                            client,
                            repository.path,
                            head,
                            revision,
                            isPullRequestTrusted(head, TaskListener.NULL),
                            ownsClient = true,
                        )
                    }

                    head is CnbPullRequestSCMHead && revision == null -> {
                        val pullRequest =
                            CnbPullRequestIdentity.fetchOpen(client, repository.path, head)
                                ?: throw IOException("CNB head '${head.name}' no longer exists")
                        val resolved =
                            CnbPullRequestSCMRevision(
                                head,
                                currentBase(client, pullRequest),
                                pullRequest.sourceSha,
                                pullRequest.mergeSha,
                            )
                        pullRequestCriteriaProbe(
                            client,
                            repository.path,
                            head,
                            resolved,
                            isPullRequestTrusted(head, TaskListener.NULL),
                            ownsClient = true,
                        )
                    }

                    revision is AbstractGitSCMSource.SCMRevisionImpl -> {
                        CnbSCMProbe.owned(client, repository.path, revision.hash, head.name, revision)
                    }

                    else -> {
                        val resolved =
                            retrieve(head, TaskListener.NULL)
                                ?: throw IOException("CNB head '${head.name}' no longer exists")
                        val hash = revisionHash(resolved)
                        CnbSCMProbe.owned(client, repository.path, hash, head.name, resolved)
                    }
                }
            } catch (failure: Exception) {
                client.close()
                throw failure
            }
        }

        private fun isPullRequestTrusted(
            head: CnbPullRequestSCMHead,
            listener: TaskListener,
        ): Boolean {
            if (head.origin == SCMHeadOrigin.DEFAULT) {
                return true
            }
            CnbSCMSourceContext(null, SCMHeadObserver.none()).withTraits(traits).newRequest(this, listener).use { request ->
                return request.isTrusted(head)
            }
        }

        /**
         * Returns the safest REST representation of a pull-request checkout for criteria checks.
         *
         * CNB does not always publish a merge commit. For a trusted local merge, the source tree is
         * checked first and the immutable target base is used as a conservative fallback. This
         * preserves target-owned Jenkinsfiles without allowing an untrusted fork to supply one.
         */
        private fun pullRequestCriteriaProbe(
            client: CnbClient,
            targetRepository: String,
            head: CnbPullRequestSCMHead,
            revision: CnbPullRequestSCMRevision,
            trusted: Boolean,
            ownsClient: Boolean,
        ): CnbSCMProbe {
            if (!trusted) {
                val trustedRevision = CnbBranchSCMRevision(head.target, revision.baseHash)
                return if (ownsClient) {
                    CnbSCMProbe.owned(
                        client,
                        targetRepository,
                        revision.baseHash,
                        head.target.name,
                        trustedRevision,
                    )
                } else {
                    CnbSCMProbe.shared(
                        client,
                        targetRepository,
                        revision.baseHash,
                        head.target.name,
                        trustedRevision,
                    )
                }
            }

            val mergeHash = revision.mergeHash?.takeIf { it.isNotBlank() }
            if (head.isMerge && mergeHash != null) {
                return if (ownsClient) {
                    CnbSCMProbe.owned(client, targetRepository, mergeHash, head.name, revision)
                } else {
                    CnbSCMProbe.shared(client, targetRepository, mergeHash, head.name, revision)
                }
            }
            if (head.isMerge) {
                return if (ownsClient) {
                    CnbSCMProbe.ownedWithFallback(
                        client,
                        head.sourceRepository,
                        revision.headHash,
                        targetRepository,
                        revision.baseHash,
                        head.name,
                        revision,
                    )
                } else {
                    CnbSCMProbe.sharedWithFallback(
                        client,
                        head.sourceRepository,
                        revision.headHash,
                        targetRepository,
                        revision.baseHash,
                        head.name,
                        revision,
                    )
                }
            }
            return if (ownsClient) {
                CnbSCMProbe.owned(client, head.sourceRepository, revision.headHash, head.name, revision)
            } else {
                CnbSCMProbe.shared(client, head.sourceRepository, revision.headHash, head.name, revision)
            }
        }

        override fun retrieveActions(
            event: SCMSourceEvent<*>?,
            listener: TaskListener,
        ): List<Action> =
            client().use { client ->
                val repository = remember(client.getRepository(repositoryPath))
                listOf(ObjectMetadataAction(repository.name, null, repository.webUrl))
            }

        override fun retrieveActions(
            head: SCMHead,
            event: SCMHeadEvent<*>?,
            listener: TaskListener,
        ): List<Action> =
            client().use { client ->
                val repository = remember(client.getRepository(repositoryPath))
                when (head) {
                    is CnbBranchSCMHead -> {
                        buildList {
                            add(ObjectMetadataAction(null, null, treeUrl(repository.webUrl, head.name)))
                            if (head.name == repository.defaultBranch) {
                                add(PrimaryInstanceMetadataAction())
                            }
                        }
                    }

                    is CnbPullRequestSCMHead -> {
                        val pullRequest = client.getPullRequest(repository.path, head.number)
                        buildList {
                            add(
                                ObjectMetadataAction(
                                    pullRequest.title,
                                    null,
                                    "${repository.webUrl.trimEnd('/')}/-/pulls/${urlSegment(pullRequest.number)}",
                                ),
                            )
                            if (pullRequest.author.isNotBlank()) {
                                add(ContributorMetadataAction(pullRequest.author, pullRequest.author, null))
                            }
                        }
                    }

                    is CnbTagSCMHead -> {
                        listOf(ObjectMetadataAction(null, null, repository.webUrl))
                    }

                    else -> {
                        emptyList()
                    }
                }
            }

        protected open fun client(): CnbClient = CnbClientFactory.create(serverId, getApiCredentialsId(), CLIENT_CONTEXT.get() ?: owner)

        private inline fun <T> withClientContext(
            context: Item?,
            block: () -> T,
        ): T {
            if (context == null) return block()
            val previous = CLIENT_CONTEXT.get()
            CLIENT_CONTEXT.set(context)
            return try {
                block()
            } finally {
                if (previous == null) CLIENT_CONTEXT.remove() else CLIENT_CONTEXT.set(previous)
            }
        }

        internal fun remember(repository: CnbRepository): CnbRepository {
            if (!repository.cloneable) {
                throw IOException("CNB Secret repository '${repository.path}' cannot be cloned by Jenkins")
            }
            if (repository.path.trim('/') != repositoryPath) {
                throw IOException(
                    "CNB API returned repository '${repository.path}' while Jenkins requested '$repositoryPath'",
                )
            }
            resolvedRepository = repository
            return repository
        }

        private fun secureCloneUrl(repository: CnbRepository): String {
            val value =
                repository.cloneUrl.takeIf { it.isNotBlank() }
                    ?: derivedCloneUrl(repository.path)
            return requireHttpsCloneUrl(value, repository.path)
        }

        private fun derivedCloneUrl(path: String): String {
            val configured = CnbGlobalConfiguration.get().findServer(serverId).normalizedWebUri()
            val clonePort =
                if (configured.scheme.equals("https", ignoreCase = true)) {
                    configured.port
                } else {
                    -1
                }
            val configuredPath = configured.path.orEmpty().trimEnd('/')
            return URI(
                "https",
                null,
                configured.host,
                clonePort,
                "$configuredPath/${path.trim('/')}",
                null,
                null,
            ).toASCIIString()
        }

        private fun requireHttpsCloneUrl(
            value: String,
            expectedRepositoryPath: String? = null,
        ): String {
            val uri = URI(value)
            require(uri.scheme.equals("https", ignoreCase = true)) { "CNB Git checkout URL must use HTTPS" }
            require(uri.userInfo == null) { "CNB Git checkout URL must not contain embedded credentials" }
            require(!uri.host.isNullOrBlank()) { "CNB Git checkout URL must have a host" }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "CNB Git checkout URL must not contain a query or fragment"
            }
            val configured = CnbGlobalConfiguration.get().findServer(serverId).normalizedWebUri()
            require(uri.host.equals(configured.host, ignoreCase = true)) {
                "CNB Git checkout URL must use the configured Web host"
            }
            val allowedPort =
                if (configured.scheme.equals("https", ignoreCase = true)) effectivePort(configured) else 443
            require(effectivePort(uri) == allowedPort) {
                "CNB Git checkout URL must use the secure port associated with the configured Web origin"
            }
            if (expectedRepositoryPath != null) {
                val configuredPath = configured.path.orEmpty().trimEnd('/')
                val expectedPath = "$configuredPath/${expectedRepositoryPath.trim('/')}"
                val actualPath = uri.path
                require(actualPath == expectedPath || actualPath == "$expectedPath.git") {
                    "CNB Git checkout URL path must match repository '$expectedRepositoryPath'"
                }
            }
            return uri.toASCIIString()
        }

        private fun effectivePort(uri: URI): Int =
            when {
                uri.port >= 0 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }

        internal fun branchesFor(
            client: CnbClient,
            repository: String,
            requested: Set<String>?,
        ): List<CnbBranch> {
            if (requested == null) {
                return client.listBranches(repository)
            }
            val branches = ArrayList<CnbBranch>(requested.size)
            for (name in requested) {
                try {
                    branches.add(client.getBranch(repository, name))
                } catch (failure: CnbApiException) {
                    if (failure.statusCode != 404) {
                        throw failure
                    }
                }
            }
            return branches
        }

        internal fun pullRequestsFor(
            client: CnbClient,
            repository: String,
            requested: Set<String>?,
        ): List<CnbPullRequest> {
            if (requested == null) {
                return client.listPullRequests(repository, "open")
            }
            val pullRequests = ArrayList<CnbPullRequest>(requested.size)
            for (number in requested) {
                try {
                    val pullRequest = client.getPullRequest(repository, number)
                    if (pullRequest.state.equals("open", ignoreCase = true)) {
                        CnbPullRequestIdentity.requireLookupMatches(pullRequest, repository, number)
                        pullRequests.add(pullRequest)
                    }
                } catch (failure: CnbApiException) {
                    if (failure.statusCode != 404) {
                        throw failure
                    }
                }
            }
            return pullRequests
        }

        private fun currentBase(
            client: CnbClient,
            pullRequest: CnbPullRequest,
        ): String =
            try {
                client.getBranch(pullRequest.targetRepo, pullRequest.targetBranch).sha
            } catch (failure: IOException) {
                pullRequest.targetSha
            }

        private fun pullRequestHead(
            pullRequest: CnbPullRequest,
            strategy: ChangeRequestCheckoutStrategy,
            includeStrategyInName: Boolean,
        ): CnbPullRequestSCMHead {
            val fork = pullRequest.fromFork || pullRequest.sourceRepo != pullRequest.targetRepo
            val name =
                buildString {
                    append("PR-").append(pullRequest.number)
                    if (includeStrategyInName) {
                        append('-').append(strategy.name.lowercase(Locale.ROOT))
                    }
                }
            return CnbPullRequestSCMHead(
                name,
                pullRequest.number,
                CnbBranchSCMHead(pullRequest.targetBranch),
                strategy,
                if (fork) SCMHeadOrigin.Fork(pullRequest.sourceRepo) else SCMHeadOrigin.DEFAULT,
                pullRequest.sourceRepo,
                pullRequest.sourceBranch,
                pullRequest.author,
                pullRequest.title,
            )
        }

        private fun revisionHash(revision: SCMRevision): String =
            when (revision) {
                is CnbPullRequestSCMRevision -> revision.headHash
                is AbstractGitSCMSource.SCMRevisionImpl -> revision.hash
                else -> throw IOException("Unsupported CNB revision type: ${revision.javaClass.name}")
            }

        private fun <H : SCMHead, R : SCMRevision> scanWitness(
            listener: TaskListener,
            kind: String,
        ): SCMSourceRequest.Witness<H, R> =
            SCMSourceRequest.Witness { head, _, matches ->
                listener.logger.printf(
                    "%s %s %s configured criteria%n",
                    kind.replaceFirstChar { it.uppercase(Locale.ROOT) },
                    head.name,
                    if (matches) "matches" else "does not match",
                )
            }

        @Extension
        @Symbol("cnb")
        class DescriptorImpl : SCMSourceDescriptor() {
            override fun getDisplayName(): String = "CNB repository"

            override fun getPronoun(): String = "Repository"

            override fun getIconClassName(): String = "symbol-git-branch-outline plugin-ionicons-api"

            override fun getTraitsDefaults(): List<SCMSourceTrait> = defaultTraits()

            val traitDescriptors: List<SCMSourceTraitDescriptor>
                get() {
                    val result = ArrayList<SCMSourceTraitDescriptor>()
                    for (descriptor in SCMSourceTrait._for(this, CnbSCMSourceContext::class.java, null)) {
                        addTraitDescriptor(result, descriptor)
                    }
                    for (descriptor in SCMSourceTrait._for(this, null, CnbSCMBuilder::class.java)) {
                        addTraitDescriptor(result, descriptor)
                    }
                    return result
                }

            val traitsDescriptors: List<SCMSourceTraitDescriptor>
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

            fun doFillApiCredentialsIdItems(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter serverId: String?,
                @QueryParameter apiCredentialsId: String?,
            ): ListBoxModel {
                val result = StandardListBoxModel()
                if (context == null) {
                    if (!jenkins.model.Jenkins
                            .get()
                            .hasPermission(jenkins.model.Jenkins.MANAGE)
                    ) {
                        return result.includeCurrentValue(apiCredentialsId.orEmpty())
                    }
                } else if (!context.hasPermission(Item.CONFIGURE)) {
                    return result.includeCurrentValue(apiCredentialsId.orEmpty())
                }
                val server = serverId?.let { id -> CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == id } }
                val requirements = server?.apiUrl?.let { URIRequirementBuilder.fromUri(it).build() }.orEmpty()
                return result
                    .includeEmptyValue()
                    .includeMatchingAs(
                        ACL.SYSTEM2,
                        context,
                        StandardCredentials::class.java,
                        requirements,
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
                @QueryParameter apiCredentialsId: String?,
                @QueryParameter checkoutCredentialsId: String?,
            ): FormValidation {
                checkConfigurePermission(context)
                return CnbScmCredentials.checkCheckoutCredentials(
                    serverId,
                    apiCredentialsId,
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
            fun doCheckRepositoryPath(
                @AncestorInPath context: jenkins.scm.api.SCMSourceOwner?,
                @QueryParameter value: String?,
            ): FormValidation {
                checkConfigurePermission(context)
                val path = value?.trim().orEmpty()
                return if (path.matches(Regex("[^/\\s]+(?:/[^/\\s]+)+"))) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Use a full CNB repository path such as namespace/project")
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
                descriptors: MutableList<SCMSourceTraitDescriptor>,
                descriptor: SCMSourceTraitDescriptor,
            ) {
                if (descriptor !is GitBrowserSCMSourceTrait.DescriptorImpl && descriptor !in descriptors) {
                    descriptors.add(descriptor)
                }
            }

            override fun createCategories(): Array<SCMHeadCategory> =
                arrayOf(
                    UncategorizedSCMHeadCategory.DEFAULT,
                    ChangeRequestSCMHeadCategory.DEFAULT,
                    TagSCMHeadCategory.DEFAULT,
                )
        }

        companion object {
            private val CLIENT_CONTEXT = ThreadLocal<Item>()

            private fun defaultTraits(): List<SCMSourceTrait> =
                arrayListOf(
                    CnbBranchDiscoveryTrait(3),
                    CnbOriginPullRequestDiscoveryTrait(1),
                    CnbForkPullRequestDiscoveryTrait(1, TrustNobody()),
                )

            private fun treeUrl(
                repositoryWebUrl: String,
                branch: String,
            ): String = "${repositoryWebUrl.trimEnd('/')}/-/tree/${urlSegment(branch)}"

            private fun urlSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
