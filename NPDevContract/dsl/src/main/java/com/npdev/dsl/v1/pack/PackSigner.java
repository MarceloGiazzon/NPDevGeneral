package com.npdev.dsl.v1.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PACK-8 Step 5: signs and verifies pack tree digests using pure Java cryptography. No external
 * tools, no native libraries -- just {@code java.security}, which is mandatory in every JDK.
 *
 * <h2>Supported algorithms</h2>
 * <ul>
 *   <li><b>Ed25519</b> -- modern, fast, compact signatures (64 bytes). Requires JDK 15+.</li>
 *   <li><b>SHA256withRSA</b> -- widely compatible, larger signatures. Works on every JDK.</li>
 * </ul>
 *
 * <h2>Keypair file format</h2>
 * Keypairs are stored as two files: {@code <name>.key} (PKCS#8 private key, Base64/PEM) and
 * {@code <name>.pub} (X.509 public key, Base64/PEM). The {@link #generateKeyPair} /
 * {@link #writeKeyPair} / {@link #readKeyPair} helpers produce and consume this format.
 *
 * <h2>What is signed</h2>
 * The UTF-8 bytes of the digest string itself (e.g. {@code "sha256:abcdef..."}). This is the same
 * digest {@link PackCache} computes over the whole pack tree -- so a valid signature proves the
 * signer attested to the exact content the consumer cached.
 */
public final class PackSigner {

    private PackSigner() {
    }

    /**
     * Generates a fresh keypair for the given algorithm.
     *
     * @throws NoSuchAlgorithmException if the algorithm is not available in this JDK (should never
     *         happen for Ed25519 on JDK 15+ or SHA256withRSA on any JDK).
     */
    public static KeyPair generateKeyPair(String algorithm) throws NoSuchAlgorithmException {
        String jcaName = jcaAlgorithmName(algorithm);
        int keySize = "SHA256withRSA".equals(algorithm) ? 2048 : -1;
        KeyPairGenerator gen = KeyPairGenerator.getInstance(jcaName);
        if (keySize > 0) {
            gen.initialize(keySize);
        }
        return gen.generateKeyPair();
    }

    /**
     * Signs the given digest string (e.g. {@code "sha256:abcdef..."}) with the private key.
     *
     * @return the raw signature bytes -- callers Base64-encode for storage in {@link PackSignature}.
     */
    public static byte[] sign(String algorithm, String digest, PrivateKey privateKey) throws Exception {
        Signature signer = Signature.getInstance(jcaSignatureAlgorithm(algorithm));
        signer.initSign(privateKey);
        signer.update(digest.getBytes(StandardCharsets.UTF_8));
        return signer.sign();
    }

    /**
     * Verifies a signature over the given digest string using the public key.
     *
     * @return true if the signature is valid, false if it does not match (tampered content or wrong
     *         key).
     */
    public static boolean verify(String algorithm, String digest, byte[] signatureBytes, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance(jcaSignatureAlgorithm(algorithm));
            verifier.initVerify(publicKey);
            verifier.update(digest.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convenience: creates a complete {@link PackSignature} by signing the digest with the given
     * keypair. The returned record embeds the Base64-encoded public key so a consumer can verify
     * without any out-of-band key distribution.
     */
    public static PackSignature createSignature(String algorithm, String digest, KeyPair keyPair) throws Exception {
        byte[] sigBytes = sign(algorithm, digest, keyPair.getPrivate());
        String encodedPubKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        return new PackSignature(
                algorithm,
                digest,
                Base64.getEncoder().encodeToString(sigBytes),
                encodedPubKey
        );
    }

    /**
     * Verifies a {@link PackSignature} against an independently computed digest. The digest MUST
     * match what the signature claims -- a mismatch means the content was altered after signing,
     * even if the cryptographic signature itself is valid.
     *
     * @param signature    the signature to verify (from pack.json's {@code signature} field)
     * @param actualDigest the digest computed from the actual pack tree content
     * @return true if both the digest matches AND the cryptographic signature is valid
     */
    public static boolean verify(PackSignature signature, String actualDigest) {
        if (!signature.digest().equals(actualDigest)) {
            return false;
        }
        try {
            byte[] sigBytes = Base64.getDecoder().decode(signature.value());
            byte[] pubKeyBytes = Base64.getDecoder().decode(signature.publicKey());
            KeyFactory kf = KeyFactory.getInstance(jcaAlgorithmName(signature.algorithm()));
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));
            return verify(signature.algorithm(), signature.digest(), sigBytes, publicKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Writes a keypair to disk as two PEM files: {@code basePath.key} (private) and
     * {@code basePath.pub} (public).
     */
    public static void writeKeyPair(Path basePath, KeyPair keyPair) throws IOException {
        Path privateKeyPath = basePath.resolveSibling(basePath.getFileName() + ".key");
        Path publicKeyPath = basePath.resolveSibling(basePath.getFileName() + ".pub");

        String privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + base64Pem(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + base64Pem(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        Files.createDirectories(basePath.getParent() == null ? Path.of(".") : basePath.getParent());
        Files.writeString(privateKeyPath, privatePem);
        Files.writeString(publicKeyPath, publicPem);
    }

    /**
     * Reads a private key from a PEM file (PKCS#8 format, as written by {@link #writeKeyPair}).
     */
    public static PrivateKey readPrivateKey(Path privateKeyPemPath, String algorithm) throws Exception {
        String pem = Files.readString(privateKeyPemPath);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance(jcaAlgorithmName(algorithm));
        return kf.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    /**
     * Reads a public key from a PEM file (X.509 format, as written by {@link #writeKeyPair}).
     */
    public static PublicKey readPublicKey(Path publicKeyPemPath, String algorithm) throws Exception {
        String pem = Files.readString(publicKeyPemPath);
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance(jcaAlgorithmName(algorithm));
        return kf.generatePublic(new X509EncodedKeySpec(encoded));
    }

    /**
     * Reads a full keypair from the two PEM files written by {@link #writeKeyPair}.
     */
    public static KeyPair readKeyPair(Path basePath, String algorithm) throws Exception {
        Path privateKeyPath = basePath.resolveSibling(basePath.getFileName() + ".key");
        Path publicKeyPath = basePath.resolveSibling(basePath.getFileName() + ".pub");
        PrivateKey privateKey = readPrivateKey(privateKeyPath, algorithm);
        PublicKey publicKey = readPublicKey(publicKeyPath, algorithm);
        return new KeyPair(publicKey, privateKey);
    }

    private static String jcaAlgorithmName(String algorithm) {
        return switch (algorithm) {
            case "Ed25519" -> "Ed25519";
            case "SHA256withRSA" -> "RSA";
            default -> throw new IllegalArgumentException("unsupported algorithm: " + algorithm);
        };
    }

    private static String jcaSignatureAlgorithm(String algorithm) {
        return switch (algorithm) {
            case "Ed25519" -> "Ed25519";
            case "SHA256withRSA" -> "SHA256withRSA";
            default -> throw new IllegalArgumentException("unsupported algorithm: " + algorithm);
        };
    }

    private static String base64Pem(byte[] bytes) {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length()));
            if (i + 64 < base64.length()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
