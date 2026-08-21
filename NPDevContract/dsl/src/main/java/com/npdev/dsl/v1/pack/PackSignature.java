package com.npdev.dsl.v1.pack;

import java.util.Base64;

/**
 * PACK-8 Step 5: a cryptographic signature over a pack tree's content digest. Mirrors the
 * cosign/Sigstore pattern -- the digest is the same SHA-256 tree hash {@link PackCache} already
 * computes for content-addressed storage, and the signature is that digest signed with a keypair
 * using pure Java cryptography ({@code java.security}, no external tools or native libraries).
 *
 * <p>The {@link #algorithm} is one of {@code "Ed25519"} or {@code "SHA256withRSA"} -- both are
 * available in every supported JDK with no extra dependencies. The {@link #digest} carries the
 * {@code sha256:<hex>} string so a verifier can confirm it matches the pack tree's own computed
 * digest before even checking the signature. The {@link #value} is the raw signature bytes
 * (Base64-encoded), and {@link #publicKey} is the signer's encoded public key (also Base64) so a
 * consumer can verify without any out-of-band key distribution -- the public key travels with the
 * signature, exactly as cosign's key-pair mode does.
 *
 * <p>Immutability: this is a record, so all fields are final and the class is intrinsically
 * thread-safe. The Base64 strings are the wire/storage format; {@link PackSigner} handles the
 * encoding/decoding to/from raw bytes at sign/verify time.
 */
public record PackSignature(
        String algorithm,
        String digest,
        String value,
        String publicKey
) {
    public PackSignature {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be blank");
        }
        if (!"Ed25519".equals(algorithm) && !"SHA256withRSA".equals(algorithm)) {
            throw new IllegalArgumentException("unsupported algorithm: " + algorithm
                    + " (supported: Ed25519, SHA256withRSA)");
        }
        if (digest == null || !digest.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("digest must match 'sha256:<64 hex chars>': " + digest);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value (base64 signature) must not be blank");
        }
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey (base64) must not be blank");
        }
        // Validate that value and publicKey are valid Base64
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("value is not valid Base64: " + e.getMessage(), e);
        }
        try {
            Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("publicKey is not valid Base64: " + e.getMessage(), e);
        }
    }
}
