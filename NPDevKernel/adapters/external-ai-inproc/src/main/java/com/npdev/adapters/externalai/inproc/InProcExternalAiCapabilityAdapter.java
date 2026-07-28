package com.npdev.adapters.externalai.inproc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiPayload;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0009's air-gapped transport (D2): "sending" a pack means writing it to a directory a human
 * then manually copies out for a paste-transport round with an external AI's chat UI; "ingesting a
 * verdict" means reading back the JSON the human pasted the response into. No network call ever
 * happens in this adapter -- it is the {@code mail-inproc} half of the {@code mail-inproc} /
 * {@code mail-smtp} pair convention, applied to external AI transport.
 */
public final class InProcExternalAiCapabilityAdapter implements CapabilityAdapter, ExternalAiCapabilityContract {

    private final Path packDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ExternalAiRunResult> runs = new ArrayList<>();
    private final List<ExternalAiVerdictRecord> verdicts = new ArrayList<>();

    public InProcExternalAiCapabilityAdapter(Path packDirectory) {
        if (packDirectory == null) {
            throw new IllegalArgumentException("packDirectory must be non-null");
        }
        this.packDirectory = packDirectory;
    }

    @Override
    public String adapterId() {
        return "external-ai-inproc";
    }

    @Override
    public String capability() {
        return "externalAi";
    }

    @Override
    public String capabilityType() {
        return "ExternalAiCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return switch (call.operation()) {
            case "submitPack" -> CapabilityResult.success(submitPackPayload(call.args()));
            case "ingestVerdict" -> CapabilityResult.success(ingestVerdictPayload(call.args()));
            default -> CapabilityResult.failure(
                    "EXTERNAL_AI_OPERATION_UNSUPPORTED",
                    "Unsupported externalAi operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        };
    }

    @Override
    public ExternalAiRunResult submitPack(ExternalAiPackSubmission submission) {
        try {
            Path missionDir = packDirectory.resolve(sanitize(submission.missionId()));
            Files.createDirectories(missionDir);
            Path packFile = missionDir.resolve(submission.packManifestSha256() + ".json");
            Files.writeString(packFile, submission.packJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing pack to air-gapped transport directory", e);
        }
        ExternalAiRunResult result = ExternalAiRunResult.run(
                submission.missionId(), submission.packManifestSha256(), submission.vendorId());
        runs.add(result);
        return result;
    }

    @Override
    public ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson) {
        JsonNode node;
        try {
            node = objectMapper.readTree(verdictJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("verdictJson is not valid JSON", e);
        }
        requireField(node, "recordKind", ExternalAiVerdictRecord.RECORD_KIND);
        requireBooleanField(node, "noRepoAccess", true);
        requireBooleanField(node, "autoApplied", false);
        String model = node.path("model").asText(null);
        ExternalAiVerdictRecord record = new ExternalAiVerdictRecord(missionId, null, vendorId, model, verdictJson);
        verdicts.add(record);
        return record;
    }

    public List<ExternalAiRunResult> runs() {
        return List.copyOf(runs);
    }

    public List<ExternalAiVerdictRecord> verdicts() {
        return List.copyOf(verdicts);
    }

    private Map<String, Object> submitPackPayload(List<Object> args) {
        ExternalAiPackSubmission submission = ExternalAiPayload.parseSubmission(args);
        ExternalAiRunResult result = submitPack(submission);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missionId", result.missionId());
        payload.put("runStatus", result.runStatus());
        payload.put("packManifestSha256", result.packManifestSha256());
        payload.put("vendorId", result.vendorId());
        return payload;
    }

    private Map<String, Object> ingestVerdictPayload(List<Object> args) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("ingestVerdict requires missionId, vendorId, verdictJson");
        }
        ExternalAiVerdictRecord record = ingestVerdict(
                String.valueOf(args.get(0)), String.valueOf(args.get(1)), String.valueOf(args.get(2)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missionId", record.missionId());
        payload.put("vendorId", record.vendorId());
        payload.put("model", record.model());
        payload.put("recordKind", ExternalAiVerdictRecord.RECORD_KIND);
        return payload;
    }

    private void requireField(JsonNode node, String field, String expected) {
        String actual = node.path(field).asText(null);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "verdictJson." + field + " must be '" + expected + "', got: " + actual);
        }
    }

    private void requireBooleanField(JsonNode node, String field, boolean expected) {
        JsonNode value = node.path(field);
        if (!value.isBoolean() || value.asBoolean() != expected) {
            throw new IllegalArgumentException(
                    "verdictJson." + field + " must be " + expected + ", got: " + value);
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
