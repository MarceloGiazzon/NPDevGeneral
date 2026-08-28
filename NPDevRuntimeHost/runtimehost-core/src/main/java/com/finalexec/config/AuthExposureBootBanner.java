package com.finalexec.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Cold Clone Audit F1: {@code auth.mode=none} hands ADMIN to every anonymous caller (the generated
 * {@code RuntimeContextService}'s {@code .withRoles(Set.of("USER", "DEBUG", "ADMIN"))}) -- a
 * deliberate dev convenience (see {@code AgentProxyController}, which is SUPERUSER-gated rather than
 * ADMIN-gated for exactly this reason), but one that was previously silent past the docs. Warn
 * loudly, once, at the exact moment of exposure: when auth is off AND the embedded server is
 * reachable from outside the machine. Spring Boot's own default -- an unset {@code server.address}
 * -- binds every interface (0.0.0.0), not just loopback, so this fires for most auth-off apps unless
 * the operator has explicitly restricted the bind address.
 */
@Component
public class AuthExposureBootBanner implements ApplicationRunner {

    private static final Set<String> LOOPBACK_ADDRESSES =
            Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1");

    private final boolean authEnabled;
    private final String serverAddress;

    public AuthExposureBootBanner(
            @Value("${npdev.auth.enabled:true}") boolean authEnabled,
            @Value("${server.address:}") String serverAddress
    ) {
        this.authEnabled = authEnabled;
        this.serverAddress = serverAddress;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (authEnabled || isLoopbackOnly(serverAddress)) {
            return;
        }
        String boundTo = serverAddress == null || serverAddress.isBlank()
                ? "0.0.0.0 (every interface)"
                : serverAddress;
        String bar = "=".repeat(72);
        System.out.println(bar);
        System.out.println("AUTH IS OFF (auth.mode=none) and this app is bound to " + boundTo + ".");
        System.out.println("Every request from your network has ADMIN. Bind to 127.0.0.1, or set");
        System.out.println("auth.mode, before anyone else can reach this.");
        System.out.println(bar);
    }

    static boolean isLoopbackOnly(String address) {
        String trimmed = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        return LOOPBACK_ADDRESSES.contains(trimmed);
    }
}
