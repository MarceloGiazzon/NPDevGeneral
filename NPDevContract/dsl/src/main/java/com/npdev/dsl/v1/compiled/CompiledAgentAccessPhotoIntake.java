package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P8 (G5): compiled form of {@code AgentAccessPhotoIntakeAst} -- what a photo sent to the bot becomes.
 * {@code null} on {@link CompiledAgentAccess#getPhotoIntake()} when the model declares none (photos
 * are then answered with a "send text" hint).
 */
public final class CompiledAgentAccessPhotoIntake {
    private final String concept;
    private final String imageField;
    private final String captionField;
    private final String procedure;
    private final Map<String, String> defaults;
    private final String description;

    public CompiledAgentAccessPhotoIntake(String concept, String imageField, String captionField, String procedure,
            Map<String, String> defaults, String description) {
        this.concept = concept;
        this.imageField = imageField;
        this.captionField = captionField;
        this.procedure = procedure;
        this.defaults = defaults == null ? new LinkedHashMap<>() : new LinkedHashMap<>(defaults);
        this.description = description;
    }

    public String getConcept() { return concept; }
    public String getImageField() { return imageField; }
    public String getCaptionField() { return captionField; }
    public String getProcedure() { return procedure; }
    public Map<String, String> getDefaults() { return Collections.unmodifiableMap(defaults); }
    public String getDescription() { return description; }
}
