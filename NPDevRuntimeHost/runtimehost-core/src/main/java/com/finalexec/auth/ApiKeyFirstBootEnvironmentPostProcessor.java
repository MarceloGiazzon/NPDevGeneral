package com.finalexec.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * OPS-1: an apikey-mode app booted with no key used to die in StartupValidator.validateAuth()
 * ("npdev.auth.api-keys must define at least one mapping") -- which is exactly what
 * docs/YOUR_FIRST_APP.md's `java -jar ... --spring.profiles.active=dev` does. This provisions the
 * SAME file every launcher already writes (OperationalRunbookEmitter Ensure-NpdevApiKey,
 * npdev_cli.ensure_api_key): {@code secrets/api-key.env}, one line
 * {@code NPDEV_AUTH_API_KEYS=<key>=dev:developer:admin}, and feeds it into the environment
 * BEFORE any bean (StartupValidator, the api-key filter) reads npdev.auth.api-keys.
 *
 * <p>Registered in META-INF/spring.factories next to JwtKeyPairEnvironmentPostProcessor -- same
 * seam, same reasoning (runs after ConfigDataEnvironmentPostProcessor, before any bean).
 *
 * <p>Never runs when: auth disabled; mode=jwt; a key is already configured (env var or file wins);
 * the {@code prod} profile is active (prod stays fail-closed on purpose); or
 * {@code npdev.auth.api-key-auto-provision=false}.
 */
public class ApiKeyFirstBootEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger LOG = Logger.getLogger(ApiKeyFirstBootEnvironmentPostProcessor.class.getName());
    static final String PROPERTY_SOURCE_NAME = "npdevFirstBootApiKey";
    static final String KEY_FILE_PROPERTY = "npdev.auth.api-key-file";
    static final String DEFAULT_KEY_FILE = "secrets/api-key.env";
    static final String ENV_LINE_PREFIX = "NPDEV_AUTH_API_KEYS=";
    static final String MAPPING_SUFFIX = "=dev:developer:admin";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Boolean.parseBoolean(environment.getProperty("npdev.auth.enabled", "true"))) {
            return;
        }
        String mode = environment.getProperty("npdev.auth.mode", "apikey").trim();
        if (!mode.isEmpty() && !"apikey".equalsIgnoreCase(mode)) {
            return;
        }
        String configured = environment.getProperty("npdev.auth.api-keys", "").trim();
        if (configured.contains("=")) {
            return; // NPDEV_AUTH_API_KEYS / NPDEV_AUTH_APIKEYS / a yml value already supplied one
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")
                || !Boolean.parseBoolean(environment.getProperty("npdev.auth.api-key-auto-provision", "true"))) {
            return;
        }
        Path keyFile = Path.of(environment.getProperty(KEY_FILE_PROPERTY, DEFAULT_KEY_FILE));
        try {
            String mapping = readMapping(keyFile);
            boolean generated = false;
            if (mapping == null) {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                mapping = HexFormat.of().formatHex(bytes) + MAPPING_SUFFIX;
                writeKeyFile(keyFile, ENV_LINE_PREFIX + mapping);
                generated = true;
            }
            environment.getPropertySources().addFirst(
                    new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of("npdev.auth.api-keys", mapping)));
            application.addListeners(new ReadyBanner(keyFile.toAbsolutePath(),
                    mapping.substring(0, mapping.indexOf('=')), generated));
        } catch (IOException | RuntimeException failure) {
            // Leave the environment untouched: StartupValidator then reports its existing, clear error.
            LOG.log(Level.WARNING, "OPS-1: could not provision a first-boot API key at " + keyFile, failure);
        }
    }

    /** Same "present but unusable is treated as absent" rule as Ensure-NpdevApiKey (REG-157). */
    static String readMapping(Path keyFile) throws IOException {
        if (!Files.isRegularFile(keyFile)) {
            return null;
        }
        for (String raw : Files.readAllLines(keyFile, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1); // Set-Content -Encoding UTF8 on Windows PowerShell 5 writes a BOM
            }
            // Accept both spellings; Ensure-NpdevApiKey/ensure_api_key write NPDEV_AUTH_API_KEYS.
            String prefix = line.startsWith(ENV_LINE_PREFIX) ? ENV_LINE_PREFIX
                    : line.startsWith("NPDEV_AUTH_APIKEYS=") ? "NPDEV_AUTH_APIKEYS=" : null;
            if (prefix != null) {
                String value = line.substring(prefix.length()).strip();
                if (value.contains("=")) {
                    return value;
                }
            }
        }
        return null;
    }

    private static void writeKeyFile(Path keyFile, String content) throws IOException {
        if (keyFile.getParent() != null) {
            Files.createDirectories(keyFile.getParent());
        }
        Files.writeString(keyFile, content, StandardCharsets.UTF_8);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** Named class, not a lambda: Spring cannot resolve a lambda listener's generic event type. */
    static final class ReadyBanner implements ApplicationListener<ApplicationReadyEvent> {
        private final Path keyFile;
        private final String rawKey;
        private final boolean generated;

        ReadyBanner(Path keyFile, String rawKey, boolean generated) {
            this.keyFile = keyFile;
            this.rawKey = rawKey;
            this.generated = generated;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", "8080");
            String bar = "=".repeat(74);
            System.out.println(bar);
            System.out.println("NPDev app ready:  http://localhost:" + port + "/");
            if (generated) {
                System.out.println("Generated an admin API key (shown once). Paste it into the API Key field:");
                System.out.println("  X-Api-Key: " + rawKey);
            } else {
                System.out.println("API key (X-Api-Key) is in: " + keyFile);
            }
            System.out.println("Saved to: " + keyFile);
            System.out.println(bar);
        }
    }
}
