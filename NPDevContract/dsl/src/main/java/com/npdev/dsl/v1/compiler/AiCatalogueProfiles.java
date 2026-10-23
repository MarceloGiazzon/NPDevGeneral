package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AIP-3 (3.4a): the {@code profiles} of the AI catalogue bundled into this jar ({@code npdev/dsl/ai-providers.json},
 * copied from {@code NPDevCli/ai-providers.json}), so an {@code externalAi.prompts[]} entry can name a profile
 * instead of a vendor and model. {@link ModelCompiler} resolves the profile to its provider, model and effort;
 * {@code ExternalAiValidation} reports an unknown one. Only the shipped catalogue: a developer's own ai.json
 * overlay is per machine and never shapes a generated app. A profile's {@code fallback} list is not carried
 * over -- an app calls exactly one vendor per prompt.
 */
public final class AiCatalogueProfiles {
    static final String RESOURCE = "npdev/dsl/ai-providers.json";

    /** One catalogue profile; {@code effort} may be null. */
    public record Profile(String name, String provider, String model, String effort) {
    }

    private static final Map<String, Profile> PROFILES = load();

    private AiCatalogueProfiles() {
    }

    public static Optional<Profile> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(PROFILES.get(name.trim()));
    }

    /** Profile names in catalogue order, for error messages. */
    public static Set<String> names() {
        return PROFILES.keySet();
    }

    private static Map<String, Profile> load() {
        try (InputStream in = AiCatalogueProfiles.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath -- the dsl build copies it "
                        + "from NPDevCli/ai-providers.json; rebuild the dsl jar");
            }
            Map<String, Profile> profiles = new LinkedHashMap<>();
            for (JsonNode profile : new ObjectMapper().readTree(in).path("profiles")) {
                if (profile.path("disabled").asBoolean(false)) {
                    continue;
                }
                String effort = profile.path("effort").asText("");
                profiles.put(profile.path("name").asText(), new Profile(
                        profile.path("name").asText(), profile.path("provider").asText(),
                        profile.path("model").asText(null), effort.isBlank() ? null : effort));
            }
            return Collections.unmodifiableMap(profiles);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + RESOURCE, e);
        }
    }
}
