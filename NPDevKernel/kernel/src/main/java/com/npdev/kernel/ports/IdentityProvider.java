package com.npdev.kernel.ports;

import java.util.Optional;

/**
 * An external identity provider behind the platform's sign-in abstraction (NPDEV_MEGA_ROADMAP.md
 * Session 3b): the OAuth 2.0 authorization-code flow that turns a browser redirect and a
 * one-time code into verified identity claims about who the user is.
 *
 * <p>The port is deliberately small because the contract is small. The RuntimeHost side owns
 * everything that must stay app-local: the state parameter (CSRF), the redirect URI, the
 * persistence of the {@link #providerId()}/{@link IdentityProviderClaims#subject()} linkage onto
 * an {@code identity::User}, and the JWT minting. An adapter supplies only the provider-specific
 * parts: where to send the browser, how to exchange the code, and what the provider vouches for.
 *
 * <p>One adapter per provider (e.g. {@code idp-google}); {@link #providerId()} is the stable key
 * used in stored account linkages and in the OAuth endpoints' URL paths, so renaming an adapter
 * class must never rename the provider id.
 */
public interface IdentityProvider {

    IdentityProvider NONE = new IdentityProvider() {
        @Override
        public String providerId() {
            return "none";
        }

        @Override
        public String authorizationUrl(String state, String redirectUri) {
            throw new UnsupportedOperationException("no identity provider is configured");
        }

        @Override
        public Optional<IdentityProviderClaims> resolveIdentity(String code, String redirectUri) {
            return Optional.empty();
        }
    };

    /** Stable provider key, e.g. {@code google}; used in stored linkages and endpoint paths. */
    String providerId();

    /**
     * The URL the browser is redirected to so the user can authorize with this provider. The
     * {@code state} value is caller-chosen (a CSRF nonce bound to the pending request); the
     * {@code redirectUri} is where the provider must send the browser back with the code.
     */
    String authorizationUrl(String state, String redirectUri);

    /**
     * Exchange a one-time authorization code for the provider's identity claims. Only claims the
     * provider actually verified may be non-empty: an unverified email address must surface as
     * {@code emailVerified == false}, never as a verified one.
     */
    Optional<IdentityProviderClaims> resolveIdentity(String code, String redirectUri);

    /** Verified identity claims about an end user of an external provider. */
    record IdentityProviderClaims(
            /** Provider-side stable subject id (Google's {@code sub}); never an email. */
            String subject,
            String email,
            boolean emailVerified,
            String displayName,
            String avatarUrl
    ) {
    }
}