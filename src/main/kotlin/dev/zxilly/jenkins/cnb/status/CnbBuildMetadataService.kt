package dev.zxilly.jenkins.cnb.status

import hudson.EnvVars
import hudson.model.AbstractProject
import hudson.model.Item
import hudson.model.Queue
import hudson.model.Run
import hudson.model.TaskListener
import jenkins.model.JenkinsLocationConfiguration
import java.util.logging.Level
import java.util.logging.Logger

internal object CnbBuildMetadataService {
    private val LOGGER = Logger.getLogger(CnbBuildMetadataService::class.java.name)

    fun reportRun(
        run: Run<*, *>,
        state: CnbBuildMetadataState,
        suppliedConfiguration: CnbBuildMetadataConfiguration? = null,
        environment: EnvVars? = null,
        listener: TaskListener = TaskListener.NULL,
        announce: Boolean = false,
    ): Boolean =
        try {
            // Explicit Pipeline/publisher calls are never disabled by a Branch Source trait. The
            // trait context remains a default, below any context supplied by the caller.
            val reportingPolicy = CnbBranchSourceReportingPolicy.forItem(run.parent)
            val jobConfiguration = publisherConfiguration(run.parent)
            val requestedContext =
                suppliedConfiguration
                    ?.overlay(jobConfiguration)
                    ?.let { expand(it, environment) }
                    ?.normalized()
                    ?.context
            val existing = selectAction(run, requestedContext)
            val persistedConfiguration = existing?.configuration()
            // A publisher or Pipeline invocation is allowed to refresh the per-run snapshot. Later
            // lifecycle callbacks have no build environment, so reapplying the raw job template
            // there would replace already-expanded values such as $CNB_REPOSITORY and clear a valid
            // target after the build completed.
            val configurationTemplate =
                when {
                    suppliedConfiguration != null -> suppliedConfiguration.overlay(jobConfiguration)
                    persistedConfiguration != null && !persistedConfiguration.isEmpty() -> persistedConfiguration
                    else -> jobConfiguration
                }
            val configuration =
                expand(configurationTemplate, environment)
            val effective = configuration.overlay(persistedConfiguration ?: CnbBuildMetadataConfiguration())
            val resolution =
                CnbBuildMetadataResolver.resolve(
                    actionable = run,
                    item = run.parent,
                    causes = run.causes,
                    explicit = effective,
                    previous = existing?.target(),
                    defaultContext = reportingPolicy.defaultContext,
                )
            if (!resolution.relevant) return false

            val target = resolution.target
            if (target == null) {
                val invalidConfiguration = !configuration.normalized().isEmpty()
                val action =
                    existing ?: if (invalidConfiguration) {
                        CnbBuildMetadataAction("run:${run.externalizableId}:${requestedContext.orEmpty()}")
                    } else {
                        null
                    }
                if (action != null) {
                    action.configure(configuration)
                    action.clearTarget()
                    if (existing == null) run.addAction(action) else persist(run)
                }
                if (announce) {
                    listener.logger.println(
                        "[CNB] Build metadata is configured but server/repository/SHA could not be resolved; no previous target will be reused.",
                    )
                }
                return false
            }

            val action =
                existing
                    ?: CnbBuildMetadataAction("run:${run.externalizableId}:${target.context}")
            action.configure(configuration)
            action.advance(target, state, run.fullDisplayName, absoluteUrl(run.url))
            if (existing == null) {
                run.addAction(action)
            } else {
                persist(run)
            }
            CnbBuildMetadataDispatcher.schedule(run, action)
            if (announce) {
                listener.logger.println("[CNB] Scheduled ${state.wireName} build metadata reconciliation (not a native commit status).")
            }
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            warn(listener, e)
            false
        } catch (e: Exception) {
            warn(listener, e)
            false
        }

    /** Advances every durable context attached to a Run; creates the default context when absent. */
    fun reportRunLifecycle(
        run: Run<*, *>,
        state: CnbBuildMetadataState,
        listener: TaskListener = TaskListener.NULL,
    ): Boolean {
        if (!CnbBranchSourceReportingPolicy.forItem(run.parent).automaticReportingEnabled) return false
        val actions = run.getActions(CnbBuildMetadataAction::class.java).toList()
        if (actions.isEmpty()) return reportRun(run, state, listener = listener)
        var scheduled = false
        for (action in actions) {
            scheduled =
                reportRun(
                    run = run,
                    state = state,
                    suppliedConfiguration = action.configuration(),
                    listener = listener,
                ) || scheduled
        }
        return scheduled
    }

    fun reportQueued(item: Queue.WaitingItem) {
        try {
            val taskItem = item.task as? Item
            val reportingPolicy = CnbBranchSourceReportingPolicy.forItem(taskItem)
            if (!reportingPolicy.automaticReportingEnabled) return
            val existing = item.getAction(CnbBuildMetadataAction::class.java)
            val configuration = publisherConfiguration(taskItem).overlay(existing?.configuration() ?: CnbBuildMetadataConfiguration())
            val resolution =
                CnbBuildMetadataResolver.resolve(
                    actionable = item,
                    item = taskItem,
                    causes = item.causes,
                    explicit = configuration,
                    previous = existing?.target(),
                    defaultContext = reportingPolicy.defaultContext,
                )
            if (!resolution.relevant) return
            val target = resolution.target
            if (target == null) {
                existing?.clearTarget()
                return
            }
            val action =
                existing
                    ?: CnbBuildMetadataAction("queue:${item.id}:${taskItem?.fullName ?: item.task.name}")
            action.configure(configuration)
            action.advance(
                target,
                CnbBuildMetadataState.QUEUED,
                "${item.task.name} (queued)",
                absoluteUrl(item.url),
            )
            if (existing == null) item.addAction(action)
            CnbBuildMetadataDispatcher.schedule(taskItem, action)
        } catch (e: Exception) {
            LOGGER.log(Level.WARNING, "Unable to prepare queued CNB metadata (${e.javaClass.simpleName})")
        }
    }

    fun reportCancelled(item: Queue.LeftItem) {
        val taskItem = item.task as? Item
        if (!CnbBranchSourceReportingPolicy.forItem(taskItem).automaticReportingEnabled) return
        val action = item.getAction(CnbBuildMetadataAction::class.java) ?: return
        action.advance(
            action.target(),
            CnbBuildMetadataState.ABORTED,
            "${item.task.name} (cancelled)",
            absoluteUrl(item.url),
        )
        CnbBuildMetadataDispatcher.schedule(taskItem, action)
    }

    fun configurationOf(publisher: CnbBuildMetadataPublisher): CnbBuildMetadataConfiguration =
        CnbBuildMetadataConfiguration(
            serverId = publisher.serverId,
            repository = publisher.repository,
            commitRepository = publisher.commitRepository,
            sha = publisher.sha,
            pullRequestNumber = publisher.pullRequestNumber,
            tag = publisher.tag,
            context = publisher.context,
            credentialsId = publisher.credentialsId,
        ).normalized()

    private fun publisherConfiguration(item: Item?): CnbBuildMetadataConfiguration {
        val project = item as? AbstractProject<*, *> ?: return CnbBuildMetadataConfiguration()
        val publisher =
            project.publishersList.get(CnbBuildMetadataPublisher::class.java)
                ?: return CnbBuildMetadataConfiguration()
        return configurationOf(publisher)
    }

    private fun selectAction(
        run: Run<*, *>,
        requestedContext: String?,
    ): CnbBuildMetadataAction? {
        val actions = run.getActions(CnbBuildMetadataAction::class.java)
        return if (requestedContext == null) {
            actions.firstOrNull { it.contextKey().isBlank() } ?: actions.firstOrNull()
        } else {
            actions.firstOrNull { it.contextKey() == requestedContext }
        }
    }

    private fun expand(
        configuration: CnbBuildMetadataConfiguration,
        environment: EnvVars?,
    ): CnbBuildMetadataConfiguration {
        if (environment == null) return configuration

        fun value(input: String?): String? = input?.let(environment::expand)?.trim()?.takeIf(String::isNotEmpty)
        return CnbBuildMetadataConfiguration(
            serverId = value(configuration.serverId),
            repository = value(configuration.repository),
            commitRepository = value(configuration.commitRepository),
            sha = value(configuration.sha),
            pullRequestNumber = value(configuration.pullRequestNumber),
            tag = value(configuration.tag),
            context = value(configuration.context),
            // Never expand credential IDs: the expanded value may be a bound secret and this
            // configuration is persisted in CnbBuildMetadataAction/build.xml.
            credentialsId = configuration.credentialsId?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun absoluteUrl(relativeUrl: String): String {
        val root =
            JenkinsLocationConfiguration
                .get()
                .url
                ?.trim()
                ?.trimEnd('/')
        return if (root.isNullOrBlank()) relativeUrl else "$root/${relativeUrl.trimStart('/')}"
    }

    private fun persist(run: Run<*, *>) {
        try {
            run.save()
        } catch (e: Exception) {
            LOGGER.log(Level.WARNING, "Unable to persist CNB build metadata action (${e.javaClass.simpleName})")
        }
    }

    private fun warn(
        listener: TaskListener,
        exception: Exception,
    ) {
        val message =
            "CNB build metadata could not be scheduled (${exception.javaClass.simpleName}); Jenkins result is unchanged"
        LOGGER.warning(message)
        if (listener !== TaskListener.NULL) listener.logger.println("[CNB] $message")
    }
}
