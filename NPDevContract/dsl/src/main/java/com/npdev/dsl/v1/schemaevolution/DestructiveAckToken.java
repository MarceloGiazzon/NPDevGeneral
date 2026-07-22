package com.npdev.dsl.v1.schemaevolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * LNCH-1 Phase 4 (task 4.2). A generator-agnostic SHA-256 acknowledgment token that binds a
 * schema fingerprint to the exact set of destructive items a migration is about to apply.
 *
 * <p>Lives in the DSL module -- not RuntimeHost, not the generator -- so BOTH sides depend on the
 * identical class file / bytecode and can never independently drift on the hashing rule: the
 * generator already depends on {@code :dsl} today, and RuntimeHost pulls the {@code :dsl} jar onto
 * its own compile/runtime classpath via the {@code runtimehost-libs} staging mechanism (it already
 * reads {@code com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson*} to load the model snapshot
 * shipped in every generated app -- confirmed on the working tree this class was added to). This
 * class itself has a deliberately generator-/runtime-agnostic signature -- plain strings and lists
 * in, a lower-case hex string out -- so neither caller needs any RuntimeHost-only or generator-only
 * type on its classpath just to compute or verify a token.
 *
 * <p>Only RuntimeHost's {@code SchemaLifecycleExecutor} actually calls this class in Phase 4. The
 * generator's own call site (threading a computed token through {@code -AcknowledgeDestructive})
 * is Phase 6 scope and is deliberately NOT built here.
 *
 * <h2>Exact hash input format (must never change without a coordinated version bump on both sides)</h2>
 * <pre>
 *   SHA-256( newSchemaFingerprint + "\n" + sortedItem_1 + "\n" + sortedItem_2 + "\n" + ... )
 * </pre>
 * i.e. the new schema fingerprint, then every destructive item's caller-supplied "stable string
 * form" (see {@code SchemaDeltaReport.Item#stableString()} on the RuntimeHost side; the generator
 * gains an equivalent producer in Phase 6), each item preceded by a single {@code "\n"} separator
 * -- so the fingerprint line and every item line are newline-joined, with no trailing newline when
 * the item list is empty. UTF-8 encoding throughout ({@link String#getBytes(java.nio.charset.Charset)}
 * with {@link StandardCharsets#UTF_8}).
 *
 * <p><b>Callers do not need to pre-sort.</b> This class sorts the supplied item strings
 * lexicographically ({@link String#compareTo(String)}) itself before hashing, so two callers that
 * build the same logical item set via different collection/iteration orders (a {@code HashMap}
 * versus a {@code LinkedHashMap}, items appended in a different discovery order, etc.) still
 * produce byte-identical hash input and therefore the identical token -- this is what makes the
 * token verifiable independently by two different derivations (the generator's preview, Phase 6,
 * and the executor's own re-derivation at boot, per §2.3 of the plan) without requiring either side
 * to agree on an item-ordering convention beyond "sorted lexicographically as strings".
 */
public final class DestructiveAckToken {

    private DestructiveAckToken() {
    }

    /**
     * Computes the acknowledgment token for a destructive schema change.
     *
     * @param newSchemaFingerprint      the schema fingerprint the migration is moving TO (never
     *                                  the "from"/stored fingerprint -- the token names the target
     *                                  state, not the state being left).
     * @param destructiveItemStableStrings the stable string form of every destructive item in the
     *                                  change (order-independent -- see class javadoc). A
     *                                  {@code null} list is treated as empty.
     * @return a lower-case hex-encoded SHA-256 digest, 64 characters long.
     */
    public static String compute(String newSchemaFingerprint, List<String> destructiveItemStableStrings) {
        String fingerprint = newSchemaFingerprint == null ? "" : newSchemaFingerprint;
        List<String> sorted = new ArrayList<>(
                destructiveItemStableStrings == null ? List.of() : destructiveItemStableStrings);
        Collections.sort(sorted);

        StringBuilder input = new StringBuilder(fingerprint);
        for (String item : sorted) {
            input.append('\n').append(item);
        }
        return sha256Hex(input.toString());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) -- unreachable in practice.
            throw new IllegalStateException("SHA-256 MessageDigest not available", exception);
        }
    }
}
