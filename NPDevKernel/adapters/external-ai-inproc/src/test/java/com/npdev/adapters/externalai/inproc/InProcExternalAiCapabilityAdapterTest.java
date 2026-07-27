package com.npdev.adapters.externalai.inproc;

import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcExternalAiCapabilityAdapterTest {

    @Test
    void submitPackWritesTheExactPackJsonToDisk(@TempDir Path tempDir) throws IOException {
        InProcExternalAiCapabilityAdapter adapter = new InProcExternalAiCapabilityAdapter(tempDir);
        String packJson = "{\"missionId\":\"M1-SEC-GENCODE\",\"chunks\":[]}";
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M1-SEC-GENCODE", "openai", "b".repeat(64), packJson);

        ExternalAiRunResult result = adapter.submitPack(submission);

        assertEquals("RUN", result.runStatus());
        assertEquals("M1-SEC-GENCODE", result.missionId());
        Path written = tempDir.resolve("M1-SEC-GENCODE").resolve("b".repeat(64) + ".json");
        assertTrue(Files.exists(written), "pack file should exist at " + written);
        assertEquals(packJson, Files.readString(written));
        assertEquals(1, adapter.runs().size());
    }

    @Test
    void ingestVerdictAcceptsAWellFormedVerdict(@TempDir Path tempDir) {
        InProcExternalAiCapabilityAdapter adapter = new InProcExternalAiCapabilityAdapter(tempDir);
        String verdictJson = "{\"recordKind\":\"external-ai-verdict\",\"noRepoAccess\":true,"
                + "\"autoApplied\":false,\"model\":\"gpt-5\",\"findings\":[]}";

        ExternalAiVerdictRecord record = adapter.ingestVerdict("M1-SEC-GENCODE", "openai", verdictJson);

        assertEquals("M1-SEC-GENCODE", record.missionId());
        assertEquals("openai", record.vendorId());
        assertEquals("gpt-5", record.model());
        assertEquals(1, adapter.verdicts().size());
    }

    @Test
    void ingestVerdictRejectsAVerdictClaimingRepoAccess(@TempDir Path tempDir) {
        InProcExternalAiCapabilityAdapter adapter = new InProcExternalAiCapabilityAdapter(tempDir);
        String verdictJson = "{\"recordKind\":\"external-ai-verdict\",\"noRepoAccess\":false,"
                + "\"autoApplied\":false,\"model\":\"gpt-5\"}";

        assertThrows(IllegalArgumentException.class,
                () -> adapter.ingestVerdict("M1-SEC-GENCODE", "openai", verdictJson));
    }

    @Test
    void ingestVerdictRejectsAVerdictThatWasAutoApplied(@TempDir Path tempDir) {
        InProcExternalAiCapabilityAdapter adapter = new InProcExternalAiCapabilityAdapter(tempDir);
        String verdictJson = "{\"recordKind\":\"external-ai-verdict\",\"noRepoAccess\":true,"
                + "\"autoApplied\":true,\"model\":\"gpt-5\"}";

        assertThrows(IllegalArgumentException.class,
                () -> adapter.ingestVerdict("M1-SEC-GENCODE", "openai", verdictJson));
    }

    @Test
    void ingestVerdictRejectsAWronglyLabelledRecordKind(@TempDir Path tempDir) {
        InProcExternalAiCapabilityAdapter adapter = new InProcExternalAiCapabilityAdapter(tempDir);
        String verdictJson = "{\"recordKind\":\"independent-human-review\",\"noRepoAccess\":true,"
                + "\"autoApplied\":false,\"model\":\"gpt-5\"}";

        assertThrows(IllegalArgumentException.class,
                () -> adapter.ingestVerdict("M1-SEC-GENCODE", "openai", verdictJson));
    }
}
