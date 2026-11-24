package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.cli.PackSignMainTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-359 (pack:signature): an embedded pack signature used to be impossible to verify, because the digest it
 * signs was a hash of the bytes of the very pack.json that then carried the signature. {@link PackSigningDigest}
 * hashes the tree with pack.json canonical and its signature member removed, so signing does not change what is
 * signed, re-formatting does not break it, and any real change does.
 */
class PackSigningDigestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PACK = """
            {
              "dslVersion": "3.0.0",
              "pack": "signed-demo",
              "version": "1.0.0",
              "concepts": [
                { "name": "Note", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;

    private static Path pack(Path dir, String text) throws Exception {
        Files.createDirectories(dir.resolve("fragments"));
        Files.writeString(dir.resolve("pack.json"), text, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("fragments/extra.json"), "{\"queries\": []}", StandardCharsets.UTF_8);
        return dir;
    }

    private static PackSignature embedded(Path dir) throws Exception {
        JsonNode node = MAPPER.readTree(dir.resolve("pack.json").toFile()).get("signature");
        return new PackSignature(node.get("algorithm").asText(), node.get("digest").asText(),
                node.get("value").asText(), node.get("publicKey").asText());
    }

    @Test
    void theDigestIgnoresTheSignatureMemberKeyOrderAndFormatting(@TempDir Path root) throws Exception {
        Path plain = pack(root.resolve("plain"), PACK);
        String reformatted = "{\"version\":\"1.0.0\",\"pack\":\"signed-demo\",\"dslVersion\":\"3.0.0\","
                + "\"concepts\":[{\"fields\":[{\"required\":true,\"id\":true,\"type\":\"uuid\",\"name\":\"id\"}],\"name\":\"Note\"}],"
                + "\"signature\":{\"algorithm\":\"Ed25519\",\"digest\":\"sha256:00\",\"value\":\"x\",\"publicKey\":\"y\"}}";
        Path other = pack(root.resolve("other"), reformatted);

        assertEquals(PackSigningDigest.of(plain), PackSigningDigest.of(other));
    }

    @Test
    void lineEndingsOfAJsonFileDoNotChangeTheDigest(@TempDir Path root) throws Exception {
        // a Windows checkout (core.autocrlf=true) turns the LF of every file into CRLF; a signature must survive it
        Path lf = pack(root.resolve("lf"), PACK);
        Path crlf = pack(root.resolve("crlf"), PACK.replace("\n", "\r\n"));
        Files.writeString(crlf.resolve("fragments/extra.json"), "{\r\n  \"queries\": []\r\n}", StandardCharsets.UTF_8);
        Files.writeString(lf.resolve("fragments/extra.json"), "{\n  \"queries\": []\n}", StandardCharsets.UTF_8);

        assertEquals(PackSigningDigest.of(lf), PackSigningDigest.of(crlf));
    }

    @Test
    void aChangedValueOrAChangedFragmentChangesTheDigest(@TempDir Path root) throws Exception {
        Path dir = pack(root.resolve("p"), PACK);
        String before = PackSigningDigest.of(dir);

        Files.writeString(dir.resolve("fragments/extra.json"), "{\"queries\": [1]}", StandardCharsets.UTF_8);
        String fragmentChanged = PackSigningDigest.of(dir);
        assertNotEquals(before, fragmentChanged);

        Files.writeString(dir.resolve("pack.json"), PACK.replace("1.0.0", "1.0.1"), StandardCharsets.UTF_8);
        assertNotEquals(fragmentChanged, PackSigningDigest.of(dir));
    }

    @Test
    void aSignedPackVerifiesAgainstTheDigestAConsumerRecomputesAndATamperedOneDoesNot(@TempDir Path root) throws Exception {
        Path dir = pack(root.resolve("p"), PACK);
        Path keyBase = root.resolve("keys").resolve("signer");
        Files.createDirectories(keyBase.getParent());

        assertEquals(0, PackSignMainTestAccess.run(new String[] {"--pack-dir", dir.toString(), "--key", keyBase.toString(), "--generate-key"}));

        String text = Files.readString(dir.resolve("pack.json"), StandardCharsets.UTF_8);
        assertTrue(text.startsWith(PACK.substring(0, PACK.lastIndexOf('}')).stripTrailing()),
                "the author's own text must stay exactly as written");
        assertEquals(1, text.split("\"signature\"", -1).length - 1);
        assertTrue(PackSigner.verify(embedded(dir), PackSigningDigest.of(dir)),
                "what a consumer recomputes must equal what was signed");

        Files.writeString(dir.resolve("fragments/extra.json"), "{\"queries\": [\"tampered\"]}", StandardCharsets.UTF_8);
        assertFalse(PackSigner.verify(embedded(dir), PackSigningDigest.of(dir)), "a tampered fragment must not verify");
    }

    @Test
    void signingAgainReplacesTheOldSignatureAndVerifies(@TempDir Path root) throws Exception {
        Path dir = pack(root.resolve("p"), PACK);
        Path keyBase = root.resolve("signer");
        PackSignMainTestAccess.run(new String[] {"--pack-dir", dir.toString(), "--key", keyBase.toString(), "--generate-key"});
        Files.writeString(dir.resolve("fragments/extra.json"), "{\"queries\": [\"edited\"]}", StandardCharsets.UTF_8);
        assertFalse(PackSigner.verify(embedded(dir), PackSigningDigest.of(dir)));

        assertEquals(0, PackSignMainTestAccess.run(new String[] {"--pack-dir", dir.toString(), "--key", keyBase.toString()}));

        String text = Files.readString(dir.resolve("pack.json"), StandardCharsets.UTF_8);
        assertEquals(1, text.split("\"signature\"", -1).length - 1, "the old signature must be replaced, not kept");
        assertTrue(PackSigner.verify(embedded(dir), PackSigningDigest.of(dir)));
    }
}
