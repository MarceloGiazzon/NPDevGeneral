package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-8 Step 5: sign + verify round trip, tamper detection, and keypair file I/O.
 */
class PackSignatureTest {

    @TempDir
    Path temp;

    // ---- Sign + verify round trip ---------------------------------------------------------------

    @Test
    void ed25519SignAndVerifyRoundTrip() throws Exception {
        KeyPair keyPair = PackSigner.generateKeyPair("Ed25519");
        String digest = "sha256:" + "a".repeat(64);

        PackSignature signature = PackSigner.createSignature("Ed25519", digest, keyPair);

        assertNotNull(signature);
        assertEquals("Ed25519", signature.algorithm());
        assertEquals(digest, signature.digest());
        assertTrue(PackSigner.verify(signature, digest));
    }

    @Test
    void sha256WithRsaSignAndVerifyRoundTrip() throws Exception {
        KeyPair keyPair = PackSigner.generateKeyPair("SHA256withRSA");
        String digest = "sha256:" + "b".repeat(64);

        PackSignature signature = PackSigner.createSignature("SHA256withRSA", digest, keyPair);

        assertNotNull(signature);
        assertEquals("SHA256withRSA", signature.algorithm());
        assertEquals(digest, signature.digest());
        assertTrue(PackSigner.verify(signature, digest));
    }

    // ---- Tamper detection -----------------------------------------------------------------------

    @Test
    void tamperedDigestIsDetected() throws Exception {
        KeyPair keyPair = PackSigner.generateKeyPair("Ed25519");
        String originalDigest = "sha256:" + "a".repeat(64);

        PackSignature signature = PackSigner.createSignature("Ed25519", originalDigest, keyPair);

        // The actual content changed, so the digest is different
        String tamperedDigest = "sha256:" + "b".repeat(64);
        assertFalse(PackSigner.verify(signature, tamperedDigest),
                "a digest that differs from what was signed must not verify");
    }

    @Test
    void tamperedSignatureBytesAreDetected() throws Exception {
        KeyPair keyPair = PackSigner.generateKeyPair("Ed25519");
        String digest = "sha256:" + "a".repeat(64);

        PackSignature original = PackSigner.createSignature("Ed25519", digest, keyPair);

        // Corrupt the signature by flipping bytes
        byte[] sigBytes = Base64.getDecoder().decode(original.value());
        sigBytes[0] ^= 0xFF;
        PackSignature tampered = new PackSignature(
                original.algorithm(),
                original.digest(),
                Base64.getEncoder().encodeToString(sigBytes),
                original.publicKey()
        );

        assertFalse(PackSigner.verify(tampered, digest),
                "a corrupted signature must not verify");
    }

    @Test
    void wrongPublicKeyIsDetected() throws Exception {
        KeyPair signerKeyPair = PackSigner.generateKeyPair("Ed25519");
        KeyPair otherKeyPair = PackSigner.generateKeyPair("Ed25519");
        String digest = "sha256:" + "a".repeat(64);

        PackSignature original = PackSigner.createSignature("Ed25519", digest, signerKeyPair);

        // Replace the public key with a different one
        String otherPubKey = Base64.getEncoder().encodeToString(otherKeyPair.getPublic().getEncoded());
        PackSignature wrongKey = new PackSignature(
                original.algorithm(),
                original.digest(),
                original.value(),
                otherPubKey
        );

        assertFalse(PackSigner.verify(wrongKey, digest),
                "a signature verified with the wrong public key must not verify");
    }

    // ---- PackSignature validation ---------------------------------------------------------------

    @Test
    void invalidAlgorithmIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new PackSignature("RSA", "sha256:" + "a".repeat(64),
                        Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                        Base64.getEncoder().encodeToString(new byte[]{4, 5, 6})));
    }

    @Test
    void invalidDigestFormatIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new PackSignature("Ed25519", "not-a-digest",
                        Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                        Base64.getEncoder().encodeToString(new byte[]{4, 5, 6})));
    }

    @Test
    void blankValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new PackSignature("Ed25519", "sha256:" + "a".repeat(64),
                        "",
                        Base64.getEncoder().encodeToString(new byte[]{4, 5, 6})));
    }

    // ---- Keypair file I/O -----------------------------------------------------------------------

    @Test
    void keypairWriteAndReadRoundTrip() throws Exception {
        KeyPair original = PackSigner.generateKeyPair("Ed25519");
        Path basePath = temp.resolve("test-key");

        PackSigner.writeKeyPair(basePath, original);

        assertTrue(Files.exists(temp.resolve("test-key.key")), "private key file must be written");
        assertTrue(Files.exists(temp.resolve("test-key.pub")), "public key file must be written");

        KeyPair loaded = PackSigner.readKeyPair(basePath, "Ed25519");
        assertNotNull(loaded.getPrivate());
        assertNotNull(loaded.getPublic());

        // The loaded keypair must be able to verify a signature made with the original
        String digest = "sha256:" + "c".repeat(64);
        PackSignature sig = PackSigner.createSignature("Ed25519", digest, original);
        assertTrue(PackSigner.verify(sig.algorithm(), sig.digest(),
                Base64.getDecoder().decode(sig.value()), loaded.getPublic()));
    }

    @Test
    void rsaKeypairWriteAndReadRoundTrip() throws Exception {
        KeyPair original = PackSigner.generateKeyPair("SHA256withRSA");
        Path basePath = temp.resolve("rsa-key");

        PackSigner.writeKeyPair(basePath, original);
        KeyPair loaded = PackSigner.readKeyPair(basePath, "SHA256withRSA");

        String digest = "sha256:" + "d".repeat(64);
        byte[] sigBytes = PackSigner.sign("SHA256withRSA", digest, loaded.getPrivate());
        assertTrue(PackSigner.verify("SHA256withRSA", digest, sigBytes, loaded.getPublic()));
    }

    // ---- Integration with PackCache-style digest ------------------------------------------------

    @Test
    void signatureOverATreeDigestVerifiesAfterStoreAndRead() throws Exception {
        // Create a minimal pack tree
        Path packTree = temp.resolve("pack-tree");
        Files.createDirectories(packTree);
        Files.writeString(packTree.resolve("pack.json"),
                """
                { "dslVersion": "1.0.0", "pack": "test", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [] } ] }
                """);

        // Store in cache to get the digest
        PackCache cache = new PackCache(temp.resolve("cache"));
        String digestHex = cache.store(packTree);
        String fullDigest = "sha256:" + digestHex;

        // Sign the digest
        KeyPair keyPair = PackSigner.generateKeyPair("Ed25519");
        PackSignature signature = PackSigner.createSignature("Ed25519", fullDigest, keyPair);

        // Read from cache and verify the digest still matches
        Path packJson = cache.read(digestHex);
        assertTrue(Files.isRegularFile(packJson));

        // The signature should verify against the cache's digest
        assertTrue(PackSigner.verify(signature, fullDigest));
    }
}
