package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates {@code config.json} against its own declared contract, at GENERATION time.
 *
 * <h2>Why this did not exist, and what that cost</h2>
 *
 * <p>Every {@code config.json} in this repo carries a {@code $schema} pointer to
 * {@code config.schema.json}. <b>Nothing read it.</b> {@code Build-NpdevApp.ps1} only <i>writes</i>
 * that property; this generator read the file with a plain {@code readTree} and never checked it; no
 * gate looked.
 *
 * <p>The first time anything did (2026-08-08, storage/FULL_SUPPORT_PLAN.md W6.1): <b>27 corpus
 * configs, 93 errors</b>, including {@code NPDevSamples/npdev-canary} -- the T1-frozen, shipped fast
 * gate canary -- failing its own contract seven times. Most of those were the SCHEMA being wrong, not
 * the configs; it demanded a {@code database} block fifteen working apps have never had, demanded
 * Postgres connection fields from engines that do not listen on a port, and forbade both the
 * {@code console} section four official apps declare and the {@code packs} section
 * {@code GeneratorMain.readInstalledPackAliases} reads on every single run.
 *
 * <p><b>A pointer that lies is worse than no pointer.</b> Widening a schema nobody enforces is
 * widening a comment, so the contract was corrected first and this check added second.
 *
 * <h2>Why the schema is copied at build time rather than mirrored by hand</h2>
 *
 * <p>{@code model.schema.json} lives in four hand-maintained places and needs a dedicated gate to
 * keep them equal. Rather than make {@code config.schema.json} the fifth copy of that problem, this
 * module's {@code processResources} copies the canonical file from
 * {@code NPDevContract/schemas/config.schema.json} into the jar. There is one editable copy, and a
 * build always carries the current one.
 *
 * <h2>Refusal, not repair</h2>
 *
 * <p>An invalid config fails generation, naming every violation. The alternative -- warn and carry on
 * with defaults -- is precisely the silent-wrong-answer shape the rest of this codebase spends its
 * gates preventing: the app generates, boots, and behaves as though a setting the author wrote were
 * absent.
 */
public final class ConfigSchemaValidator {

    private static final String SCHEMA_RESOURCE_PATH = "/schema/config.schema.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ConfigSchemaValidator() {
    }

    /**
     * Refuse generation when {@code config} violates {@code config.schema.json}.
     *
     * @param config     the parsed config, or {@code null} when the caller was given no config path
     *                   (a supported mode -- the generator can run on CLI arguments alone)
     * @param configPath the path, for the message; a violation the reader cannot locate is a riddle
     * @throws IllegalStateException listing every violation
     */
    public static void verify(JsonNode config, String configPath) {
        if (config == null) {
            return;
        }
        Set<ValidationMessage> violations = schema().validate(config);
        if (violations.isEmpty()) {
            return;
        }
        String detail = violations.stream()
                .map(message -> "  " + message.getInstanceLocation() + ": " + message.getMessage())
                .sorted()
                .collect(Collectors.joining("\n"));
        throw new IllegalStateException(
                "config.json does not satisfy config.schema.json (" + violations.size()
                + " violation(s)):\n" + detail
                + "\n\nFile: " + (configPath == null ? "(inline)" : configPath)
                + "\nContract: NPDevContract/schemas/config.schema.json"
                + "\n\nThis is checked because the file's own $schema property has always claimed it. "
                + "If the contract is what is wrong, fix the schema and mirror it to all three copies "
                + "(scripts/quality/check-config-schema.py validates the whole corpus against it).");
    }

    private static JsonSchema schema() {
        try (InputStream stream = ConfigSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            if (stream == null) {
                // Not a warning. A validator that silently does nothing when its schema is missing is
                // the same defect as no validator, wearing a green tick -- and this resource is copied
                // in by the build, so its absence means the build is wrong.
                throw new IllegalStateException(
                        "config schema resource not found on the classpath: " + SCHEMA_RESOURCE_PATH
                        + ". It is copied from NPDevContract/schemas/config.schema.json by this "
                        + "module's processResources -- if it is missing, that copy step is broken.");
            }
            JsonNode schemaJson = OBJECT_MAPPER.readTree(stream);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaJson);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to load the config schema resource: " + SCHEMA_RESOURCE_PATH, exception);
        }
    }
}
