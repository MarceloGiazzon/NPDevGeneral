package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P8 (G5): {@code agentAccess.photoIntake} -- what a photo sent to the bot (Telegram/WhatsApp) becomes.
 * The image is uploaded into {@code imageField} of a new {@code concept} row; the optional
 * {@code procedure} runs over that draft first (e.g. AI identification) and its answer pre-fills the
 * row; {@code captionField} receives the photo's caption; {@code defaults} fill the rest
 * ({@code "$user.username"} = the linked user). The user always confirms before the row is created,
 * and every step runs through the app's REST API as that user.
 */
public final class AgentAccessPhotoIntakeAst {
    private final String concept;
    private final String imageField;
    private final String captionField;
    private final String procedure;
    private final Map<String, String> defaults;
    private final String description;

    public AgentAccessPhotoIntakeAst(String concept, String imageField, String captionField, String procedure,
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
