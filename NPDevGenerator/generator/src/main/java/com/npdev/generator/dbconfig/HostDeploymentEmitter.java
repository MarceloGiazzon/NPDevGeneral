package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Writes the external-deployment manifests for {@code npdev host deploy} (H13/H14) -- but ONLY
 * when {@code _ops/host-plan.json} (written just before this by {@link HostPlanEmitter}, H16)
 * names an external target. An app hosted locally (rung 0-2, no target) gets nothing from this
 * class: writing a render.yaml nobody asked for is not a convenience, it is noise in every
 * generated app's diff.
 *
 * <p>Modeled on {@link DockerDeploymentEmitter}: text blocks, a {@code write(...)} helper,
 * everything target-specific coming from {@link HostTargetProfile} data rather than branching
 * prose (H13 anchor). The manifest CONTENT itself comes from {@code requiredEnv} in
 * {@code host-plan.json} -- the one place that decides which variables a deployment needs, pinned
 * across seams by twin-pair rule {@code host-plan-required-env-seams} (H17).
 *
 * <p><b>Determinism</b> (LANDMINES #8): no timestamps, no random ids, no HashMap iteration --
 * {@code requiredEnv} is read back in the insertion order {@link HostPlanEmitter} wrote it in
 * (Jackson's {@code ObjectNode} preserves insertion order), so the same model produces
 * byte-identical manifests across two runs.
 */
public final class HostDeploymentEmitter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void emit(JsonNode config, Path finalAppRoot, GeneratedDatabasePlan databasePlan, Path hostPlanPath)
            throws Exception {
        if (hostPlanPath == null || !Files.isRegularFile(hostPlanPath)) {
            return;
        }
        JsonNode plan = MAPPER.readTree(hostPlanPath.toFile());
        String targetId = plan.hasNonNull("target") ? plan.path("target").asText() : null;
        if (targetId == null) {
            return;
        }
        Optional<HostTargetProfile> maybeTarget = HostTargetProfiles.of(targetId);
        if (maybeTarget.isEmpty()) {
            return;
        }
        HostTargetProfile target = maybeTarget.get();

        JsonNode requiredEnv = plan.path("requiredEnv");
        String appId = databasePlan.appId();
        int serverPort = readInt(config, 8080, "runtime", "serverPort");

        switch (targetId) {
            case "render-neon", "render-h2" ->
                    write(finalAppRoot.resolve("render.yaml"), renderYaml(appId, serverPort, target, requiredEnv));
            case "koyeb-tidb" ->
                    write(finalAppRoot.resolve("koyeb.yaml"), koyebYaml(appId, serverPort, target, requiredEnv));
            default -> {
                return;
            }
        }
        write(finalAppRoot.resolve(".env." + targetId + ".example"), envExample(targetId, requiredEnv));
    }

    private static String renderYaml(String appId, int serverPort, HostTargetProfile target, JsonNode requiredEnv) {
        StringBuilder envVars = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = requiredEnv.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode info = entry.getValue();
            envVars.append("        - key: ").append(entry.getKey()).append('\n');
            if (info.hasNonNull("value")) {
                envVars.append("          value: \"").append(info.path("value").asText()).append("\"\n");
            } else {
                // sync: false -- Render's own way of saying "set this in the dashboard", never baked
                // into the manifest. Matches requiredEnv's source: "you" exactly.
                envVars.append("          sync: false\n");
            }
        }
        return """
                # Hosting (H13): generated for target '%s' from this app's _ops/host-plan.json.
                # Connect this repository at https://dashboard.render.com/ -- Render builds
                # Dockerfile.build (P6's self-building variant) and deploys the result. The engine
                # this app was generated with is BAKED IN; changing it here does nothing (see the
                # 'engine-matches-target' check in `npdev host check`).
                services:
                  - type: web
                    name: %s
                    runtime: docker
                    dockerfilePath: ./Dockerfile.build
                    plan: starter
                    envVars:
                %s\
                """.formatted(target.id(), appId, envVars);
    }

    private static String koyebYaml(String appId, int serverPort, HostTargetProfile target, JsonNode requiredEnv) {
        StringBuilder envVars = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = requiredEnv.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode info = entry.getValue();
            String value = info.hasNonNull("value") ? info.path("value").asText() : "";
            envVars.append("      - name: ").append(entry.getKey()).append('\n');
            envVars.append("        value: \"").append(value).append("\"\n");
        }
        return """
                # Hosting (H13): generated for target '%s' from this app's _ops/host-plan.json.
                # Deploy with the Koyeb CLI (`koyeb app init` / `koyeb deploy`) or by connecting this
                # repository at https://app.koyeb.com/ -- Koyeb builds Dockerfile.build (P6's
                # self-building variant). Entries with an empty value must be filled in on the Koyeb
                # dashboard/CLI before the first deploy -- they are never baked in here.
                name: %s
                services:
                  - name: web
                    type: web
                    ports:
                      - port: %d
                        protocol: http
                    env:
                %s\
                """.formatted(target.id(), appId, serverPort, envVars);
    }

    private static String envExample(String targetId, JsonNode requiredEnv) {
        StringBuilder body = new StringBuilder();
        body.append("# Hosting (H13): the complete environment contract for target '")
                .append(targetId).append("', from _ops/host-plan.json.\n")
                .append("# Copy this file to .env.").append(targetId)
                .append(" and fill in every line NOT already given a value below.\n\n");
        Iterator<Map.Entry<String, JsonNode>> fields = requiredEnv.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode info = entry.getValue();
            body.append("# ").append(info.path("why").asText()).append('\n');
            if (info.hasNonNull("value")) {
                body.append(entry.getKey()).append('=').append(info.path("value").asText()).append("\n\n");
            } else {
                body.append(entry.getKey()).append("=\n\n");
            }
        }
        return body.toString();
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    private static int readInt(JsonNode root, int fallback, String... path) {
        JsonNode current = root;
        if (current == null) {
            return fallback;
        }
        for (String element : path) {
            current = current.path(element);
        }
        return current.isMissingNode() || current.isNull() ? fallback : current.asInt(fallback);
    }
}
