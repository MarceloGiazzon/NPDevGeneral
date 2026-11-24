package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackSignature;
import com.npdev.dsl.v1.pack.PackSigner;
import com.npdev.dsl.v1.pack.PackSigningDigest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REG-359: signs a pack directory IN PLACE -- writes (or replaces) the {@code signature} member of its
 * {@code pack.json}, keeping the rest of the file's text exactly as the author wrote it.
 *
 * <pre>
 *   PackSignMain --pack-dir &lt;dir&gt; --key &lt;keypair-base&gt; [--generate-key]
 * </pre>
 *
 * The signed digest is {@link PackSigningDigest#of(Path)}: the tree with {@code pack.json} canonical and its
 * signature removed, so adding the signature does not change what it signs and {@code PackAddMain} can recompute
 * it. Re-run this after ANY edit of the pack (a fragment or a value): a stale signature is refused by {@code pack add}.
 * {@code <keypair-base>.key} / {@code .pub} are PEM files; keep the private key OUTSIDE the repository.
 */
public final class PackSignMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SIGNATURE_AFTER_COMMA =
            Pattern.compile(",\\s*\"signature\"\\s*:\\s*\\{[^{}]*\\}");
    private static final Pattern SIGNATURE_BEFORE_COMMA =
            Pattern.compile("\"signature\"\\s*:\\s*\\{[^{}]*\\}\\s*,\\s*");

    private PackSignMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String packDirArg = null;
        String keyArg = null;
        boolean generate = false;
        for (int i = 0; i < args.length; i++) {
            if ("--pack-dir".equals(args[i]) && i + 1 < args.length) {
                packDirArg = args[++i];
            } else if ("--key".equals(args[i]) && i + 1 < args.length) {
                keyArg = args[++i];
            } else if ("--generate-key".equals(args[i])) {
                generate = true;
            }
        }
        if (packDirArg == null || keyArg == null) {
            System.err.println("usage: PackSignMain --pack-dir <dir> --key <keypair-base> [--generate-key]");
            return 64;
        }
        ObjectNode report = MAPPER.createObjectNode();
        try {
            Path packDir = Path.of(packDirArg);
            Path packJson = packDir.resolve("pack.json");
            Path keyBase = Path.of(keyArg);
            if (!Files.isRegularFile(packJson)) {
                throw new IllegalArgumentException("no pack.json in " + packDir);
            }
            KeyPair keyPair;
            if (generate && !Files.exists(Path.of(keyArg + ".key"))) {
                keyPair = PackSigner.generateKeyPair("Ed25519");
                PackSigner.writeKeyPair(keyBase, keyPair);
                report.put("generatedKey", keyBase.toString());
            } else {
                keyPair = PackSigner.readKeyPair(keyBase, "Ed25519");
            }

            String text = Files.readString(packJson, StandardCharsets.UTF_8);
            String unsigned = withoutSignature(text);
            Files.writeString(packJson, unsigned, StandardCharsets.UTF_8);

            String digest = PackSigningDigest.of(packDir);
            PackSignature signature = PackSigner.createSignature("Ed25519", digest, keyPair);
            Files.writeString(packJson, withSignature(unsigned, signature), StandardCharsets.UTF_8);

            // read it back: what was written must verify against what a consumer will recompute
            ObjectNode written = (ObjectNode) MAPPER.readTree(packJson.toFile());
            var node = written.get("signature");
            PackSignature readBack = new PackSignature(
                    node.get("algorithm").asText(), node.get("digest").asText(),
                    node.get("value").asText(), node.get("publicKey").asText());
            boolean verifies = PackSigner.verify(readBack, PackSigningDigest.of(packDir));
            report.put("status", verifies ? "signed" : "failed");
            report.put("digest", digest);
            report.put("packJson", packJson.toString());
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return verifies ? 0 : 2;
        } catch (Exception failure) {
            report.put("status", "failed");
            report.put("error", String.valueOf(failure.getMessage()));
            System.out.println(report);
            return 2;
        }
    }

    /** The pack.json text with any existing {@code signature} member removed (it is a flat object of strings). */
    static String withoutSignature(String text) {
        Matcher after = SIGNATURE_AFTER_COMMA.matcher(text);
        if (after.find()) {
            return after.replaceFirst("");
        }
        Matcher before = SIGNATURE_BEFORE_COMMA.matcher(text);
        if (before.find()) {
            return before.replaceFirst("");
        }
        return text;
    }

    /** The pack.json text with the signature added as the last member of the root object, in the file's own style. */
    static String withSignature(String unsigned, PackSignature signature) {
        String eol = unsigned.contains("\r\n") ? "\r\n" : "\n";
        int close = unsigned.lastIndexOf('}');
        if (close < 0) {
            throw new IllegalArgumentException("pack.json has no closing brace");
        }
        String head = unsigned.substring(0, close).stripTrailing();
        String tail = unsigned.substring(close);
        StringBuilder out = new StringBuilder(head);
        out.append(',').append(eol)
                .append("  \"signature\": {").append(eol)
                .append("    \"algorithm\": \"").append(signature.algorithm()).append("\",").append(eol)
                .append("    \"digest\": \"").append(signature.digest()).append("\",").append(eol)
                .append("    \"value\": \"").append(signature.value()).append("\",").append(eol)
                .append("    \"publicKey\": \"").append(signature.publicKey()).append('"').append(eol)
                .append("  }").append(eol).append(tail);
        return out.toString();
    }
}
