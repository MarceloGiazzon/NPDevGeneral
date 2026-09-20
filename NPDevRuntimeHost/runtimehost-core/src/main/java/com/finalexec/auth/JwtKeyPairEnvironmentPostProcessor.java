package com.finalexec.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * REG-228: a JWT-mode app's signing keypair lives outside the three regeneration-spared directories
 * (data/logs/secrets), so any regeneration -- or a first-ever generation -- silently leaves it
 * missing, and {@code LoginController}/{@code ChangePasswordController} then fail bean creation with
 * a raw {@code NoSuchFileException}, crashing the whole app before {@link
 * com.npdev.adapters.runtime.validation.StartupValidator} ever gets a chance to report a clear error.
 * This blocked two separate sessions (REG-217's, REG-227's) doing otherwise-unrelated work.
 *
 * <p>Registered in {@code META-INF/spring.factories} as an {@link EnvironmentPostProcessor} -- same
 * mechanism and same reasoning as {@link com.finalexec.db.H2LocalBootLockEnvironmentPostProcessor}:
 * this runs once the {@code Environment} is fully resolved (profile-specific YAML like
 * {@code application-wmsoffice.yml} already merged in) but strictly BEFORE any bean, including
 * {@code LoginController}, is created -- the one seam early enough to provision the files before
 * anything tries to read them. Deliberately does NOT implement {@link org.springframework.core.Ordered}
 * for the same reason that class doesn't: default (lowest) precedence runs after
 * {@code ConfigDataEnvironmentPostProcessor}, so profile overrides of the key paths are already
 * resolved here.
 *
 * <p>Mirrors the existing {@code Ensure-NpdevApiKey} dev-convenience pattern (Build-NpdevApp.ps1):
 * generate a throwaway credential when one is missing, rather than leaving the app permanently
 * unbootable until a human notices and runs openssl by hand. Chosen over the alternative fix
 * candidate (adding the key directory to the three-seam regeneration-spared list) because the key
 * directory's name is app-configurable (e.g. {@code NPDEV_WMSOFFICE_KEYS_DIR}), not fixed across
 * apps, and because this also covers a FIRST-ever generation, not just regeneration of an app that
 * once had a keypair.
 */
public class JwtKeyPairEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger LOG = Logger.getLogger(JwtKeyPairEnvironmentPostProcessor.class.getName());
    private static final int RSA_KEY_SIZE_BITS = 2048;
    private static final int PEM_LINE_LENGTH = 64;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String mode = environment.getProperty("npdev.auth.mode", "");
        if (!"jwt".equalsIgnoreCase(mode)) {
            return;
        }
        String publicKeyPath = environment.getProperty("npdev.auth.jwt.public-key-path", "").trim();
        String privateKeyPath = environment.getProperty("npdev.auth.jwt.private-key-path", "").trim();
        // A blank path is a legitimate verify-only deployment (REG-9): nothing to provision.
        // classpath: paths name a key shipped inside the jar -- never auto-generated or overwritten.
        if (publicKeyPath.isBlank() || privateKeyPath.isBlank()
                || publicKeyPath.startsWith("classpath:") || privateKeyPath.startsWith("classpath:")) {
            return;
        }
        Path publicPath = Path.of(publicKeyPath);
        Path privatePath = Path.of(privateKeyPath);
        // Only provision when BOTH are missing. One present and the other missing is a real
        // misconfiguration (a half-deleted or hand-edited pair) -- surfacing that as a clear
        // StartupValidator error is more honest than silently minting a mismatched replacement half.
        if (Files.exists(publicPath) || Files.exists(privatePath)) {
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE_BITS);
            KeyPair keyPair = generator.generateKeyPair();
            writePem(privatePath, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
            writePem(publicPath, "PUBLIC KEY", keyPair.getPublic().getEncoded());
            LOG.warning(() -> "REG-228: no JWT signing keypair found at '" + privateKeyPath + "' / '"
                    + publicKeyPath + "' -- generated a fresh throwaway RSA-" + RSA_KEY_SIZE_BITS
                    + " dev keypair. Any token minted under a PREVIOUS keypair is now invalid.");
        } catch (Exception generationFailure) {
            // Don't block boot differently than before this fix: leave both files absent so
            // StartupValidator's own check reports its existing clear "does not point at a readable
            // key file" message instead of this class failing boot with a less legible cause.
            LOG.log(Level.WARNING, "REG-228: failed to auto-provision a JWT signing keypair at '"
                    + privateKeyPath + "' / '" + publicKeyPath + "'", generationFailure);
        }
    }

    private static void writePem(Path path, String label, byte[] derBytes) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String base64 = Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(derBytes);
        String pem = "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
        Files.writeString(path, pem, StandardCharsets.UTF_8);
    }
}
