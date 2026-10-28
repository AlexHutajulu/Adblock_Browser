package com.example

import com.example.manager.SiteDomainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteDomainPolicyTest {

    @Test
    fun indonesianCoIdSubdomainsAreSameSite() {
        assertEquals("bca.co.id", SiteDomainPolicy.registrableDomain("klikbca.bca.co.id"))
        assertEquals("bca.co.id", SiteDomainPolicy.registrableDomain("www.bca.co.id"))
        assertTrue(SiteDomainPolicy.isSameSite("klikbca.bca.co.id", "statik.bca.co.id"))
        assertFalse(SiteDomainPolicy.isThirdParty("statik.bca.co.id", "klikbca.bca.co.id"))
    }

    @Test
    fun differentCoIdDomainsAreThirdParty() {
        assertFalse(SiteDomainPolicy.isSameSite("bca.co.id", "mandiri.co.id"))
        assertTrue(SiteDomainPolicy.isThirdParty("tracker.lain.co.id", "bca.co.id"))
    }

    @Test
    fun multiTenantGithubIoTenantsAreIsolated() {
        assertEquals("alice.github.io", SiteDomainPolicy.registrableDomain("alice.github.io"))
        assertEquals("bob.github.io", SiteDomainPolicy.registrableDomain("bob.github.io"))
        assertTrue(SiteDomainPolicy.isThirdParty("bob.github.io", "alice.github.io"))
        assertFalse(SiteDomainPolicy.isThirdParty("sub.alice.github.io", "alice.github.io"))
    }

    @Test
    fun standardComSubdomainsAreSameSite() {
        assertTrue(SiteDomainPolicy.isSameSite("cdn.kompas.com", "www.kompas.com"))
        assertFalse(SiteDomainPolicy.isThirdParty("asset.kompas.com", "kompas.com"))
    }
}
