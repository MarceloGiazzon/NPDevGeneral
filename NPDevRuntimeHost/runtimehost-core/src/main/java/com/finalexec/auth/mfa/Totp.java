package com.finalexec.auth.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SEC-10: RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s step) with the JDK only -- the exact parameters
 * every authenticator app (Google Authenticator, Microsoft Authenticator, 1Password, Authy) uses by
 * default. Pure functions, no Spring, no DB: unit-testable against the RFC 6238 Appendix B vectors.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30L;
    /** Accept the previous and next step too (clock drift); RFC 6238 section 5.2. */
    public static final int WINDOW = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** 20 random bytes (160 bits, RFC 4226 recommendation), base32 without padding. */
    public static String newSecretBase32() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return base32Encode(secret);
    }

    public static long stepAt(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
    }

    /** The code for one step; 6-digit, zero-padded. */
    public static String codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /**
     * @return the matched step (store it as lastUsedStep), or -1. A step <= lastUsedStep is refused:
     *         a code can be used once (replay protection, RFC 6238 section 5.2).
     */
    public static long verify(String base32Secret, String code, Instant now, long lastUsedStep) {
        if (code == null) return -1;
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) return -1;
        byte[] key = base32Decode(base32Secret);
        long current = stepAt(now);
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            if (step <= lastUsedStep) continue;
            if (MessageDigest.isEqual(codeAt(key, step).getBytes(StandardCharsets.US_ASCII),
                    digits.getBytes(StandardCharsets.US_ASCII))) {
                return step;
            }
        }
        return -1;
    }

    /** otpauth://totp/Issuer:account?secret=...&issuer=Issuer -- what an authenticator app scans/opens. */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** 10 one-time recovery codes like "k7m2-q9xd" (base32 lower-case, 40 bits each). */
    public static List<String> newRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] raw = new byte[5];
            RANDOM.nextBytes(raw);
            String s = base32Encode(raw).toLowerCase(Locale.ROOT);   // 8 chars
            codes.add(s.substring(0, 4) + "-" + s.substring(4, 8));
        }
        return codes;
    }

    /** Recovery codes are stored only as SHA-256 hex of the normalized code (no dash, lower-case). */
    public static String recoveryCodeHash(String code) {
        String normalized = code == null ? "" : code.replace("-", "").replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- base32 (RFC 4648, no padding) --------------------------------------------------------

    public static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(B32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    public static byte[] base32Decode(String text) {
        String s = text.replace("=", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("not base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
