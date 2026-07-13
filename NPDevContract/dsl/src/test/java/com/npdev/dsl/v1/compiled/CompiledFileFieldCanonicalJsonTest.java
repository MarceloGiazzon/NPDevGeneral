package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-OBJSTORE: pins the fix for a bug where a {@code file}-typed field's
 * contentTypes/maxSizeBytes/multiple constraints were silently dropped on the
 * compiled-model.json round-trip (the hand-rolled writer/reader never carried {@link
 * CompiledField#getFile()}), which meant every generated app's runtime {@code CompiledModel}
 * always saw {@code file() == null} for a file field -- defeating
 * {@code FileUploadController}'s upload-time content-type/size allowlist validation.
 */
class CompiledFileFieldCanonicalJsonTest {

    @Test
    void fileFieldMetadataSurvivesTheCanonicalJsonRoundTrip() throws Exception {
        CompiledFileMetadata fileMeta = new CompiledFileMetadata(List.of("image/png", "application/pdf"), 2_000_000L, true);
        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.npdev.kernel.ports.FileHandle",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                fileMeta
        );
        CompiledConcept doc = new CompiledConcept("Doc", "Doc", "docs", List.of(idField, attachmentField));
        CompiledModel model = new CompiledModel("harden.objstore.roundtrip", "1.0.0", "1.0.0", Map.of(doc.getName(), doc));

        String json = CompiledModelCanonicalJson.toJson(model);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);

        CompiledConcept restoredDoc = restored.getConcepts().stream()
                .filter(c -> c.getName().equals("Doc")).findFirst().orElseThrow();
        CompiledField restoredAttachment = restoredDoc.getFields().stream()
                .filter(f -> f.getName().equals("attachment")).findFirst().orElseThrow();
        CompiledField restoredId = restoredDoc.getFields().stream()
                .filter(f -> f.getName().equals("id")).findFirst().orElseThrow();

        CompiledFileMetadata restoredMeta = restoredAttachment.getFile();
        assertNotNull(restoredMeta, "file metadata must survive the compiled-model.json round-trip");
        assertEquals(List.of("image/png", "application/pdf"), restoredMeta.contentTypes());
        assertEquals(2_000_000L, restoredMeta.maxSizeBytes());
        assertTrue(restoredMeta.multiple());

        assertNull(restoredId.getFile(), "a non-file field must not gain file metadata");
    }
}
