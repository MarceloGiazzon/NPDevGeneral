package com.finalexec.controlpanel;

import com.finalexec.npdev.service.CredentialRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ControlPanel's equivalent of {@code BootstrapAdminController}/{@code WorkspaceMenuSeeder}'s
 * self-disabling first-boot seed: on every boot, if no ACTIVE credential with role
 * {@code SUPERUSER} exists yet, issues one via {@link CredentialRegistryService} (hash-at-rest,
 * shown here exactly once) and prints it prominently. Deliberately NOT part of the identity pack
 * (identity_users/identity_roles/identity_user_roles) -- the Super User is a distinct concept from
 * any tenant's Admin User, and never appears in a tenant's own user list.
 *
 * <p>The raw key is ALSO written to {@code SUPER_USER_KEY.txt} in the process's working directory
 * (the {@code App} folder, when launched normally), not just printed to stdout -- a console banner
 * requires the operator to be watching at the exact moment of first boot, or know to dig through a
 * log file; a standing file at a fixed name is something a non-specialist author can be told to
 * open directly. {@code Start-App.ps1}/{@code Reissue-SuperUserKey.ps1} relocate it into the
 * `_ops` folder right after startup and announce the exact path, which is also what
 * `control-panel.html`'s own unlock instructions point to (see {@code New-ControlPanelPage.ps1}).</p>
 *
 * <p>Recovery for a lost key: since there's no authenticated Super User yet to ask for a reissue
 * (the same chicken-and-egg problem {@code BootstrapAdminController} has), the only safe path is
 * operator/filesystem-level, not a network endpoint -- starting the app once with
 * {@code npdev.superuser.force-reissue=true} (see the {@code Reissue-SuperUserKey.ps1} ops script)
 * revokes any existing ACTIVE SUPERUSER credential first, so this bootstrapper's normal
 * "issue if none exists" check then issues a genuinely fresh one.</p>
 */
@Component
public class SuperUserBootstrapper implements ApplicationRunner {

    private static final String SUPERUSER_ROLE = "SUPERUSER";
    private static final String SYSTEM_TENANT_ID = "__system__";
    private static final String SYSTEM_ACTOR_ID = "superuser";

    private final CredentialRegistryService credentialRegistryService;
    private final boolean forceReissue;

    public SuperUserBootstrapper(
            CredentialRegistryService credentialRegistryService,
            @Value("${npdev.superuser.force-reissue:false}") boolean forceReissue
    ) {
        this.credentialRegistryService = credentialRegistryService;
        this.forceReissue = forceReissue;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Map<String, Object>> credentials = credentialRegistryService.list();
            if (forceReissue) {
                credentials.stream()
                        .filter(SuperUserBootstrapper::isActiveSuperUser)
                        .forEach(row -> credentialRegistryService.revoke(String.valueOf(row.get("credentialId"))));
                System.out.println("[SuperUserBootstrapper] npdev.superuser.force-reissue=true -- "
                        + "revoked any existing Super User credential(s), issuing a fresh one.");
            } else if (credentials.stream().anyMatch(SuperUserBootstrapper::isActiveSuperUser)) {
                return;
            }
            Map<String, Object> issued = credentialRegistryService.issue(
                    SYSTEM_TENANT_ID, SYSTEM_ACTOR_ID, Set.of(SUPERUSER_ROLE));
            String rawKey = String.valueOf(issued.get("apiKey"));
            printBanner(rawKey);
            writeKeyFile(rawKey);
        } catch (IllegalStateException noPhysicalDatabase) {
            System.out.println("[SuperUserBootstrapper] Skipped: no physical database configured "
                    + "(Super User credentials require H2Local/H2Server/Postgres).");
        }
    }

    private static boolean isActiveSuperUser(Map<String, Object> credentialRow) {
        return "ACTIVE".equals(credentialRow.get("status"))
                && String.valueOf(credentialRow.get("roles")).toUpperCase(Locale.ROOT).contains(SUPERUSER_ROLE);
    }

    private static void printBanner(String rawKey) {
        String bar = "=".repeat(72);
        System.out.println(bar);
        System.out.println("SUPER USER KEY -- shown once, save it now.");
        System.out.println("Sign in to /control-panel.html with:");
        System.out.println("  " + rawKey);
        System.out.println(bar);
    }

    private static void writeKeyFile(String rawKey) {
        try {
            Path file = Path.of("SUPER_USER_KEY.txt");
            Files.writeString(file, rawKey + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println("[SuperUserBootstrapper] Also saved to: " + file.toAbsolutePath());
        } catch (IOException exception) {
            System.out.println("[SuperUserBootstrapper] Could not write SUPER_USER_KEY.txt ("
                    + exception.getMessage() + ") -- use the banner above instead.");
        }
    }
}
