package com.npdev.kernel.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bounds the length of an idempotency key before it is used as storage key material (REG-36).
 *
 * <p><b>The asymmetry that made this a bug.</b> The cached success <em>value</em> has always been
 * bounded ({@code KernelRunner.IDEMPOTENCY_RESULT_MAX_CHARS}). The <em>key</em> that addresses it was
 * not, even though it is the more caller-influenced of the two: it comes either from a model author's
 * {@code idempotencyKeyField} pointing at request data, or straight off an {@code Idempotency-Key}
 * request header. An oversized key exceeds Postgres's btree index-entry limit, so the post-success
 * cache write throws -- reporting an already-successful call as failed, and, because nothing was
 * cached, letting the caller's retry execute the operation a second time. That defeats the exact
 * guarantee idempotency exists to provide.</p>
 *
 * <h2>Why the encoding is what it is</h2>
 *
 * <p>The naive fix -- "digest anything too long" -- introduces a collision the original bug did not
 * have. If an oversized key {@code X} is stored as {@code sha256(X)}, then a caller who submits the
 * <em>literal short string</em> {@code sha256(X)} lands on the same record and is served someone
 * else's cached result. So the two forms have to be distinguishable by construction:</p>
 *
 * <ul>
 *   <li>a key that is short and does not already begin with {@link #DIGEST_PREFIX} is stored
 *       <b>unchanged</b> -- which is what keeps every key ever written before this change findable;</li>
 *   <li>anything else -- too long, <em>or</em> short but starting with the prefix -- is stored as
 *       {@code DIGEST_PREFIX + sha256hex}.</li>
 * </ul>
 *
 * <p>The second half of that second clause is the part that closes the collision: because <em>every</em>
 * stored key beginning with the prefix is a digest of its own input, forging one requires a SHA-256
 * preimage rather than a string comparison.</p>
 *
 * <p>The digest is not a security boundary on its own -- it is a length bound. Two distinct keys map
 * to the same record only on a SHA-256 collision.</p>
 */
public final class IdempotencyKeys {

    /**
     * Longer than any legitimate key, short enough to stay far inside Postgres's ~2704-byte btree
     * index-entry limit once the rest of the primary key (tenant, capability, operation) is added.
     */
    public static final int MAX_CHARS = 200;

    /** Marks a stored key as a digest. Deliberately unlikely to open a hand-written key. */
    public static final String DIGEST_PREFIX = "npdev-sha256$";

    private IdempotencyKeys() {
    }

    /**
     * The value to actually store/look up for {@code raw}.
     *
     * @return {@code null}/blank unchanged (callers already treat those as "no idempotency"), a short
     *         ordinary key unchanged, and anything else as {@code npdev-sha256$<hex>}
     */
    public static String bound(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (raw.length() <= MAX_CHARS && !raw.startsWith(DIGEST_PREFIX)) {
            return raw;
        }
        return DIGEST_PREFIX + sha256Hex(raw);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every conforming JRE; if it is genuinely absent, silently
            // falling back to a weaker bound would be worse than refusing to run.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
        }
    }
}
