package com.finalexec.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-228: proves the auto-provisioned keypair (1) only appears when it should, and (2) is genuinely
 * usable by both sides that read it -- {@link JwtSigner#loadPrivateKey} (the mint side) and the same
 * X.509/{@code PUBLIC KEY} parsing {@code JwtBearerAuthFilter} uses (the verify side, reimplemented
 * here since that class lives in the top-level RuntimeHost module, not runtimehost-core).
 */
class JwtKeyPairEnvironmentPostProcessorTest {

    private final JwtKeyPairEnvironmentPostProcessor processor = new JwtKeyPairEnvironmentPostProcessor();

    @Test
    @DisplayName("apikey mode never touches the filesystem")
    void apiKeyModeIsANoOp(@TempDir Path tempDir) {
        Path publicPath = tempDir.resolve("jwt-public.pem");
        Path privatePath = tempDir.resolve("jwt-private-pkcs8.pem");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.auth.mode", "apikey");
        environment.setProperty("npdev.auth.jwt.public-key-path", publicPath.toString());
        environment.setProperty("npdev.auth.jwt.private-key-path", privatePath.toString());

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertFalse(Files.exists(publicPath));
        assertFalse(Files.exists(privatePath));
    }

    @Test
    @DisplayName("a blank private-key-path (verify-only deployment, REG-9) is never touched")
    void verifyOnlyDeploymentIsANoOp(@TempDir Path tempDir) {
        Path publicPath = tempDir.resolve("jwt-public.pem");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.auth.mode", "jwt");
        environment.setProperty("npdev.auth.jwt.public-key-path", publicPath.toString());
        environment.setProperty("npdev.auth.jwt.private-key-path", "");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertFalse(Files.exists(publicPath));
    }

    @Test
    @DisplayName("a classpath: key path is never auto-generated or overwritten")
    void classpathKeyPathIsANoOp(@TempDir Path tempDir) {
        Path privatePath = tempDir.resolve("jwt-private-pkcs8.pem");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.auth.mode", "jwt");
        environment.setProperty("npdev.auth.jwt.public-key-path", "classpath:npdev/security/test-jwt-public.pem");
        environment.setProperty("npdev.auth.jwt.private-key-path", privatePath.toString());

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertFalse(Files.exists(privatePath));
    }

    @Test
    @DisplayName("an existing private key (public missing) is left alone -- a half-deleted pair is a real misconfiguration, not silently replaced")
    void oneKeyPresentIsNeverOverwritten() throws Exception {
        Path tempDir = Files.createTempDirectory("reg228-half-pair");
        try {
            Path publicPath = tempDir.resolve("jwt-public.pem");
            Path privatePath = tempDir.resolve("jwt-private-pkcs8.pem");
            Files.writeString(privatePath, "-----BEGIN PRIVATE KEY-----\nEXISTING\n-----END PRIVATE KEY-----\n");
            MockEnvironment environment = new MockEnvironment();
            environment.setProperty("npdev.auth.mode", "jwt");
            environment.setProperty("npdev.auth.jwt.public-key-path", publicPath.toString());
            environment.setProperty("npdev.auth.jwt.private-key-path", privatePath.toString());

            processor.postProcessEnvironment(environment, new SpringApplication());

            assertFalse(Files.exists(publicPath), "must not mint a replacement half for a mismatched pair");
            assertTrue(Files.readString(privatePath).contains("EXISTING"), "must not touch the existing file either");
        } finally {
            Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    @DisplayName("both keys missing in jwt mode generates a real, matched, usable RSA keypair")
    void bothMissingGeneratesAUsableMatchedKeyPair(@TempDir Path tempDir) throws Exception {
        Path publicPath = tempDir.resolve("nested").resolve("jwt-public.pem");
        Path privatePath = tempDir.resolve("nested").resolve("jwt-private-pkcs8.pem");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.auth.mode", "jwt");
        environment.setProperty("npdev.auth.jwt.public-key-path", publicPath.toString());
        environment.setProperty("npdev.auth.jwt.private-key-path", privatePath.toString());

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertTrue(Files.exists(publicPath));
        assertTrue(Files.exists(privatePath));

        // Mint side: exactly what LoginController/ChangePasswordController do at bean creation.
        String privatePem = Files.readString(privatePath, StandardCharsets.UTF_8);
        PrivateKey privateKey = JwtSigner.loadPrivateKey(privatePem);

        // Verify side: exactly what JwtBearerAuthFilter.loadPublicKey does (reimplemented here since
        // that class lives outside runtimehost-core).
        String publicPem = Files.readString(publicPath, StandardCharsets.UTF_8);
        String normalized = publicPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));

        // The two halves must genuinely belong to the SAME pair: sign with one, verify with the other.
        byte[] message = "reg-228-roundtrip".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(message);
        byte[] signatureBytes = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(message);
        assertTrue(verifier.verify(signatureBytes), "public key must verify a signature made with the private key");
    }

    @Test
    @DisplayName("running twice does not overwrite the keypair generated the first time")
    void secondRunIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path publicPath = tempDir.resolve("jwt-public.pem");
        Path privatePath = tempDir.resolve("jwt-private-pkcs8.pem");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("npdev.auth.mode", "jwt");
        environment.setProperty("npdev.auth.jwt.public-key-path", publicPath.toString());
        environment.setProperty("npdev.auth.jwt.private-key-path", privatePath.toString());

        processor.postProcessEnvironment(environment, new SpringApplication());
        byte[] firstPrivate = Files.readAllBytes(privatePath);

        processor.postProcessEnvironment(environment, new SpringApplication());
        byte[] secondPrivate = Files.readAllBytes(privatePath);

        assertArrayEquals(firstPrivate, secondPrivate, "a second boot must not mint a fresh keypair over an already-provisioned one");
    }
}
