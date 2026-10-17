package com.finalexec.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SEC-9: the env-driven OIDC provider list parser. */
class OidcProviderConfigTest {

    @Test
    void twoValidProvidersAreParsedInOrderWithDefaults() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("npdev.auth.oidc.providers", "kc, okta")
                .withProperty("npdev.auth.oidc.kc.issuer", "https://kc.example/realms/x")
                .withProperty("npdev.auth.oidc.kc.client-id", "cid")
                .withProperty("npdev.auth.oidc.kc.client-secret", "sec")
                .withProperty("npdev.auth.oidc.kc.label", "Company SSO")
                .withProperty("npdev.auth.oidc.okta.issuer", "https://dev.okta.com/oauth2/default")
                .withProperty("npdev.auth.oidc.okta.client-id", "oid")
                .withProperty("npdev.auth.oidc.okta.client-secret", "osec")
                .withProperty("npdev.auth.oidc.okta.assume-email-verified", "true")
                .withProperty("npdev.auth.oidc.okta.scopes", "openid email");

        List<OidcProviderConfig.Entry> entries = OidcProviderConfig.fromEnvironment(env);

        assertEquals(2, entries.size());
        OidcProviderConfig.Entry kc = entries.get(0);
        assertEquals("kc", kc.settings().providerId());
        assertEquals("Company SSO", kc.label());
        assertEquals("openid email profile", kc.settings().scopes());
        assertEquals("email", kc.settings().emailClaim());
        assertEquals("name", kc.settings().nameClaim());
        assertEquals("picture", kc.settings().pictureClaim());
        assertEquals(false, kc.settings().assumeEmailVerified());
        assertTrue(kc.allowedDomains().isEmpty());
        OidcProviderConfig.Entry okta = entries.get(1);
        assertEquals("okta", okta.label(), "label defaults to the id");
        assertEquals("openid email", okta.settings().scopes());
        assertEquals(true, okta.settings().assumeEmailVerified());
    }

    @Test
    void reservedAndInvalidIdsAreSkipped() {
        MockEnvironment env = new MockEnvironment().withProperty("npdev.auth.oidc.providers", "github,Bad_Id,ok");
        for (String id : List.of("github", "Bad_Id", "ok")) {
            env.withProperty("npdev.auth.oidc." + id + ".issuer", "https://i.example")
                    .withProperty("npdev.auth.oidc." + id + ".client-id", "c")
                    .withProperty("npdev.auth.oidc." + id + ".client-secret", "s");
        }
        List<OidcProviderConfig.Entry> entries = OidcProviderConfig.fromEnvironment(env);
        assertEquals(1, entries.size());
        assertEquals("ok", entries.get(0).settings().providerId());
    }

    @Test
    void aProviderMissingARequiredKeyIsSkipped() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("npdev.auth.oidc.providers", "kc")
                .withProperty("npdev.auth.oidc.kc.issuer", "https://kc.example")
                .withProperty("npdev.auth.oidc.kc.client-id", "cid");
        assertTrue(OidcProviderConfig.fromEnvironment(env).isEmpty());
    }

    @Test
    void allowedDomainsAreTrimmedAndLowerCased() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("npdev.auth.oidc.providers", "kc")
                .withProperty("npdev.auth.oidc.kc.issuer", "https://kc.example")
                .withProperty("npdev.auth.oidc.kc.client-id", "cid")
                .withProperty("npdev.auth.oidc.kc.client-secret", "sec")
                .withProperty("npdev.auth.oidc.kc.allowed-domains", " Acme.com, b.org ");
        assertEquals(Set.of("acme.com", "b.org"), OidcProviderConfig.fromEnvironment(env).get(0).allowedDomains());
        assertEquals(Set.of("x.io"), OidcProviderConfig.domains(" X.io ,"));
    }

    @Test
    void noProvidersConfiguredYieldsAnEmptyList() {
        assertTrue(OidcProviderConfig.fromEnvironment(new MockEnvironment()).isEmpty());
        assertTrue(OidcProviderConfig.fromEnvironment(null).isEmpty());
    }
}
