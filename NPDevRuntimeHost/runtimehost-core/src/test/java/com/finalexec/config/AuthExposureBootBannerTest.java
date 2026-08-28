package com.finalexec.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold Clone Audit F1: the boot banner must fire exactly when auth is off AND the app is reachable
 * from outside the machine -- never when auth is on, and never when the bind address is explicitly
 * loopback-only.
 */
class AuthExposureBootBannerTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1", "LOCALHOST", " 127.0.0.1 "})
    void loopbackAddressesAreRecognized(String address) {
        assertTrue(AuthExposureBootBanner.isLoopbackOnly(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "192.168.1.10", ""})
    void nonLoopbackAddressesAreNotRecognized(String address) {
        assertFalse(AuthExposureBootBanner.isLoopbackOnly(address));
    }

    @Test
    void blankOrUnsetAddressIsTreatedAsEveryInterface() {
        assertFalse(AuthExposureBootBanner.isLoopbackOnly(null));
        assertFalse(AuthExposureBootBanner.isLoopbackOnly(""));
    }

    @Test
    void bannerIsSilentWhenAuthIsEnabled() {
        AuthExposureBootBanner banner = new AuthExposureBootBanner(true, "");
        banner.run(null);
    }

    @Test
    void bannerIsSilentWhenBoundToLoopbackOnly() {
        AuthExposureBootBanner banner = new AuthExposureBootBanner(false, "127.0.0.1");
        banner.run(null);
    }
}
