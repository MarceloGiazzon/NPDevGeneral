package com.finalexec.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * AI7-1: one fingerprint function for the review trail. {@code ExternalAiPromptRunner} stamps the
 * hash of the answer it handed the flow into its audit row; {@code HumanTaskService} stamps the hash
 * of the proposal the reviewer actually saw into ITS audit row. Equal hashes prove the values a
 * reviewer approved are the values the vendor produced. Maps are key-sorted first so a flow-state
 * round trip (which may reorder keys) cannot change the hash.
 */
public final class ProposalHash {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ProposalHash() {
    }

    /** SHA-256 (hex) of the value's key-sorted JSON; null hashes as the JSON text {@code null}. */
    public static String ofJson(Object value) {
        try {
            return ofText(CANONICAL.writeValueAsString(value));
        } catch (Exception exception) {
            throw new IllegalStateException("value is not JSON-serializable: " + exception.getMessage(), exception);
        }
    }

    public static String ofText(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
