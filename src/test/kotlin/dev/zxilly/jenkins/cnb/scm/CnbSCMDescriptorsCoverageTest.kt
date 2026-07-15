package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.util.FormValidation
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbSCMDescriptorsCoverageTest {
    @Test
    fun `discovery trait descriptors publish strategies categories and trust policies`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val branch = CnbBranchDiscoveryTrait.DescriptorImpl()
        val tag = CnbTagDiscoveryTrait.DescriptorImpl()
        val origin = CnbOriginPullRequestDiscoveryTrait.DescriptorImpl()
        val fork = CnbForkPullRequestDiscoveryTrait.DescriptorImpl()

        assertEquals(listOf("1", "2", "3"), branch.doFillStrategyIdItems().map { it.value })
        assertEquals(listOf("1", "2", "3"), origin.doFillStrategyIdItems().map { it.value })
        assertEquals(listOf("1", "2", "3"), fork.doFillStrategyIdItems().map { it.value })
        assertEquals("Discover branches", branch.displayName)
        assertEquals("Discover tags", tag.displayName)
        assertEquals("Discover pull requests from origin", origin.displayName)
        assertEquals("Discover pull requests from forks", fork.displayName)
        assertEquals(CnbSCMSourceContext::class.java, branch.contextClass)
        assertEquals(CnbSCMSource::class.java, tag.sourceClass)
        assertInstanceOf(TrustNobody::class.java, fork.getDefaultTrust())
        assertTrue(fork.getTrustDescriptors().any { it is TrustNobody.DescriptorImpl })
    }

    @Test
    fun `authority descriptors constrain origins and expose production labels`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val branch = CnbBranchDiscoveryTrait.BranchAuthority.DescriptorImpl()
        val tag = CnbTagDiscoveryTrait.TagAuthority.DescriptorImpl()
        val origin = CnbOriginPullRequestDiscoveryTrait.OriginPullRequestAuthority.DescriptorImpl()
        val nobody = TrustNobody.DescriptorImpl()
        val sameNamespace = TrustSameNamespace.DescriptorImpl()
        val everyone = TrustEveryone.DescriptorImpl()

        assertEquals("Trust CNB branches", branch.displayName)
        assertEquals("Trust CNB tags", tag.displayName)
        assertEquals("Trust origin pull requests", origin.displayName)
        assertEquals("Nobody", nobody.displayName)
        assertEquals("Forks in the same namespace", sameNamespace.displayName)
        assertEquals("Everyone", everyone.displayName)
        assertTrue(branch.isApplicableToOrigin(SCMHeadOrigin.Default::class.java))
        assertFalse(branch.isApplicableToOrigin(SCMHeadOrigin.Fork::class.java))
        assertTrue(tag.isApplicableToOrigin(SCMHeadOrigin.Default::class.java))
        assertTrue(origin.isApplicableToOrigin(SCMHeadOrigin.Default::class.java))
        assertTrue(nobody.isApplicableToOrigin(SCMHeadOrigin.Fork::class.java))
        assertTrue(sameNamespace.isApplicableToOrigin(SCMHeadOrigin.Fork::class.java))
        assertTrue(everyone.isApplicableToOrigin(SCMHeadOrigin.Fork::class.java))
    }

    @Test
    fun `source descriptor exposes defaults credentials and validation`(jenkins: JenkinsRule) {
        val server = CnbServer("primary", "Primary", "https://cnb.cool", "https://api.cnb.cool")
        CnbGlobalConfiguration.get().setServers(listOf(server))
        val descriptor = CnbSCMSource.DescriptorImpl()

        assertEquals("CNB repository", descriptor.displayName)
        assertEquals("Repository", descriptor.pronoun)
        assertEquals("symbol-git-branch-outline plugin-ionicons-api", descriptor.iconClassName)
        assertEquals(3, descriptor.traitsDefaults.size)
        assertEquals(3, descriptor.categories.size)
        assertEquals(descriptor.traitDescriptors, descriptor.traitsDescriptors)
        assertEquals(listOf("primary", "legacy"), descriptor.doFillServerIdItems("legacy").map { it.value })
        assertTrue(descriptor.doFillApiCredentialsIdItems(null, "primary", null).any { it.value.isEmpty() })
        assertTrue(descriptor.doFillCheckoutCredentialsIdItems(null, "primary", null).any { it.value.isEmpty() })
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckServerId(null, "primary").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckServerId(null, "unknown").kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRepositoryPath(null, "team/repo").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRepositoryPath(null, "repo").kind)
        assertEquals(
            FormValidation.Kind.OK,
            descriptor.doCheckCheckoutCredentialsId(null, "primary", null, null).kind,
        )
    }

    @Test
    fun `strategy objects retain exact checkout selections`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        assertTrue(CnbBranchDiscoveryTrait(1).buildBranchesWithoutPullRequests)
        assertFalse(CnbBranchDiscoveryTrait(1).buildBranchesWithPullRequests)
        assertFalse(CnbBranchDiscoveryTrait(2).buildBranchesWithoutPullRequests)
        assertTrue(CnbBranchDiscoveryTrait(2).buildBranchesWithPullRequests)
        assertEquals(setOf(ChangeRequestCheckoutStrategy.MERGE), CnbOriginPullRequestDiscoveryTrait(1).strategies)
        assertEquals(setOf(ChangeRequestCheckoutStrategy.HEAD), CnbOriginPullRequestDiscoveryTrait(2).strategies)
        assertEquals(
            setOf(ChangeRequestCheckoutStrategy.HEAD, ChangeRequestCheckoutStrategy.MERGE),
            CnbForkPullRequestDiscoveryTrait(3, TrustEveryone()).strategies,
        )
    }
}
