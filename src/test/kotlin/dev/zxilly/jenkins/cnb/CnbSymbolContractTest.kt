package dev.zxilly.jenkins.cnb

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.config.CnbWebhookCredentials
import dev.zxilly.jenkins.cnb.credentials.CnbTokenBinding
import dev.zxilly.jenkins.cnb.credentials.CnbTokenCredentials
import dev.zxilly.jenkins.cnb.publisher.CnbPullRequestActionPublisher
import dev.zxilly.jenkins.cnb.scm.CnbBranchDiscoveryTrait
import dev.zxilly.jenkins.cnb.scm.CnbBranchPropertyFilterTrait
import dev.zxilly.jenkins.cnb.scm.CnbForkPullRequestDiscoveryTrait
import dev.zxilly.jenkins.cnb.scm.CnbOriginPullRequestDiscoveryTrait
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestCommentTriggerTrait
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestFilterTrait
import dev.zxilly.jenkins.cnb.scm.CnbReportingContextTrait
import dev.zxilly.jenkins.cnb.scm.CnbRepositoryBrowser
import dev.zxilly.jenkins.cnb.scm.CnbSCMNavigator
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.scm.CnbSkipReportingTrait
import dev.zxilly.jenkins.cnb.scm.CnbTagDiscoveryTrait
import dev.zxilly.jenkins.cnb.scm.TrustEveryone
import dev.zxilly.jenkins.cnb.scm.TrustMembers
import dev.zxilly.jenkins.cnb.scm.TrustNobody
import dev.zxilly.jenkins.cnb.scm.TrustSameNamespace
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataPublisher
import dev.zxilly.jenkins.cnb.trigger.CnbPushTrigger
import org.jenkinsci.Symbol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CnbSymbolContractTest {
    @Test
    fun `all non Pipeline symbols remain stable`() {
        val contracts =
            linkedMapOf<Class<*>, String>(
                CnbGlobalConfiguration::class.java to "cnb",
                CnbServer.DescriptorImpl::class.java to "cnbServer",
                CnbWebhookCredentials.DescriptorImpl::class.java to "cnbWebhookCredentials",
                CnbTokenCredentials.DescriptorImpl::class.java to "cnbToken",
                CnbTokenBinding.DescriptorImpl::class.java to "cnbToken",
                CnbPullRequestActionPublisher.DescriptorImpl::class.java to "cnbPullRequestAction",
                CnbBuildMetadataPublisher.DescriptorImpl::class.java to "cnbBuildMetadataPublisher",
                CnbPushTrigger.DescriptorImpl::class.java to "cnbPush",
                CnbSCMSource.DescriptorImpl::class.java to "cnb",
                CnbSCMNavigator.DescriptorImpl::class.java to "cnbOrganization",
                CnbRepositoryBrowser.DescriptorImpl::class.java to "cnbBrowser",
                CnbBranchDiscoveryTrait.DescriptorImpl::class.java to "cnbBranchDiscovery",
                CnbBranchDiscoveryTrait.BranchAuthority.DescriptorImpl::class.java to "cnbBranchAuthority",
                CnbTagDiscoveryTrait.DescriptorImpl::class.java to "cnbTagDiscovery",
                CnbTagDiscoveryTrait.TagAuthority.DescriptorImpl::class.java to "cnbTagAuthority",
                CnbOriginPullRequestDiscoveryTrait.DescriptorImpl::class.java to "cnbPullRequestDiscovery",
                CnbForkPullRequestDiscoveryTrait.DescriptorImpl::class.java to "cnbForkPullRequestDiscovery",
                CnbBranchPropertyFilterTrait.DescriptorImpl::class.java to "cnbBranchPropertyFilter",
                CnbPullRequestFilterTrait.DescriptorImpl::class.java to "cnbPullRequestFilter",
                CnbPullRequestCommentTriggerTrait.DescriptorImpl::class.java to "cnbPullRequestCommentTrigger",
                CnbSkipReportingTrait.DescriptorImpl::class.java to "cnbSkipReporting",
                CnbReportingContextTrait.DescriptorImpl::class.java to "cnbReportingContext",
                TrustNobody.DescriptorImpl::class.java to "cnbTrustNobody",
                TrustSameNamespace.DescriptorImpl::class.java to "cnbTrustSameNamespace",
                TrustEveryone.DescriptorImpl::class.java to "cnbTrustEveryone",
                TrustMembers.DescriptorImpl::class.java to "cnbTrustMembers",
            )

        assertEquals(26, contracts.size)
        contracts.forEach { (type, expected) ->
            val annotation = type.getAnnotation(Symbol::class.java)
            assertNotNull(annotation, "${type.name} must retain @Symbol")
            assertEquals(listOf(expected), requireNotNull(annotation).value.toList(), type.name)
        }
    }
}
