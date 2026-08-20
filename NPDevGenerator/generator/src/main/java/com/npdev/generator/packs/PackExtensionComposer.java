package com.npdev.generator.packs;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.pack.PackSealednessAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PACK-10 steps 2-3: additive-only, in-place extension of a base pack's concept by a SEPARATE
 * extension pack -- "a hospital variant patches a nullable field onto identity::User" without
 * forking identity itself. Deliberately a different mechanism from PACK-10 step 1's {@code
 * satelliteOf} (a 1:1 FK-linked satellite concept in its own table, already DONE and live-proven):
 * this class patches fields directly onto the BASE concept's own compiled shape, so every existing
 * consumer of the base concept sees the new field on the same table/entity, not a second one.
 *
 * <p><b>Why this is NOT built on {@code com.npdev.dsl.v1.pack.PackDiffEngine}/{@code
 * PackPublishGate}, despite the roadmap card assuming "PK-4's diff engine" as the mechanism.</b>
 * Read closely, PK-4's diff engine classifies the difference between two VERSIONS of the SAME pack
 * (its whole job is enforcing semver bump size at publish time -- see {@code PackPublishGate}'s own
 * class doc). An extension pack is a structurally different document from the pack it targets (a
 * different {@code pack} id, typically a handful of fields on one concept, not a full copy of the
 * base pack with edits) -- there is no well-formed "old pack json" / "new pack json" pair to hand
 * {@code PackDiffEngine.diff} here; the two documents being compared are not two versions of one
 * pack, they are two DIFFERENT packs. This composer performs its own field-shape comparison instead
 * (see {@link #sameShape}), but reuses the SAME conservative philosophy {@code PackDiffEngine}
 * established: a change this engine cannot prove safe is refused, never silently allowed through.
 *
 * <p><b>Declaring an extension target requires no schema change.</b> {@code pack.schema.json}'s
 * {@code metadata} key is (deliberately, already) an untyped {@code {"type":"object"}} with no
 * {@code additionalProperties:false} restriction -- see {@code NPDevContract/schemas/pack.schema.json}
 * -- so an extension pack declares its target with {@code "metadata": {"extends": {"pack": "...",
 * "concept": "..."}}}, which validates against the EXISTING schema unmodified. Introducing a proper
 * first-class {@code extends} keyword on {@code pack.json} (both schema mirrors) is real, but
 * separately-scoped follow-up work -- flagged in this slice's own report rather than done here,
 * since {@code pack.schema.json} is outside this composer's owned file surface.
 *
 * <p><b>Sealedness gate.</b> A pack that {@link PackSealednessAnalyzer} certifies sealed is eligible
 * to ship as a precompiled jar (see {@link SealedPackBuilder}) -- once shipped that way, its concepts
 * are compiled bytecode, not JSON, so there is no source shape left to patch. This composer refuses
 * to extend ANY pack {@code PackSealednessAnalyzer} certifies sealed, not only one that has actually
 * been sealed+linked in this particular build -- sealedness is a property of the pack's own content,
 * independent of whether any given app happens to link it as a jar today (matching {@code
 * PackSealednessAnalyzer}'s own "computed directly from the pack's own pack.json content" design).
 * In this repo, every real built-in/demo pack ({@code identity}, {@code workspace},
 * {@code project-tracker-demo}) is a leaf pack with no unbound capabilities, so all three certify
 * sealed today -- extending any of them correctly refuses (see {@code PackExtensionComposerTest}'s
 * real-pack refusal proof against {@code identity}). A pack meant to be extended must stay unsealed
 * (declare a {@code requires.capabilities[]} entry, or depend on another pack via {@code packs[]})
 * for as long as in-place extension is meant to remain possible.
 *
 * <p><b>Step 4 (UI composition with app-controlled ordering).</b> {@link #composeExtensionsWithOrdering}
 * builds on the merge above to answer two questions a UI renderer needs that pure field-shape
 * composition alone does not: which pack added a given field (for attribution), and where it should
 * render. Default: base fields, then each extension's added fields in processing order -- the sane
 * fallback when the app declares nothing. The APP can override via {@code metadata.fieldOrder} on its
 * own root model.json (see {@link #readFieldOrderOverrides}); an extension's own field declaration
 * order never determines final position -- see this class's own opening paragraph on why that matters.
 */
public final class PackExtensionComposer {

    /** A pack-qualified extension target: {@code {"pack": "identity", "concept": "User"}}. */
    public record ExtensionTarget(String packAlias, String conceptName) {
        public ExtensionTarget {
            Objects.requireNonNull(packAlias, "packAlias");
            Objects.requireNonNull(conceptName, "conceptName");
        }

        public String qualifiedName() {
            return packAlias + "::" + conceptName;
        }
    }

    /**
     * One extension pack's contribution: its own alias, its raw {@code pack.json} (read for its
     * {@code metadata.extends} target), and its own compiled concept (the SAME bare concept name as
     * the base's, compiled and prefixed with THIS pack's own alias by the normal compile pipeline --
     * e.g. an extension pack aliased {@code clinical} declaring a concept named {@code User} compiles
     * to {@code clinical::User}; only its fields beyond the base's own are actually merged in).
     */
    public record ExtensionSource(String extensionAlias, JsonNode extensionPackJson, CompiledConcept extensionConcept) {
    }

    /**
     * Reads this pack's own declared extension target, or {@code null} if this pack declares none
     * (an ordinary, non-extending pack). Checks the first-class {@code extends} property first
     * (PACK-10/R8.11), falling back to the legacy {@code metadata.extends} convention for backward
     * compatibility.
     */
    public ExtensionTarget readExtensionTarget(JsonNode packJson) {
        if (packJson == null || !packJson.isObject()) {
            return null;
        }
        // First-class `extends` (PACK-10/R8.11)
        JsonNode extends_ = packJson.get("extends");
        if (extends_ != null && extends_.isObject()) {
            String targetPack = textOrNull(extends_.get("pack"));
            String targetConcept = textOrNull(extends_.get("concept"));
            if (targetPack != null && targetConcept != null) {
                return new ExtensionTarget(targetPack, targetConcept);
            }
        }
        // Legacy fallback: metadata.extends
        JsonNode metadata = packJson.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode metaExtends = metadata.get("extends");
        if (metaExtends == null || !metaExtends.isObject()) {
            return null;
        }
        String targetPack = textOrNull(metaExtends.get("pack"));
        String targetConcept = textOrNull(metaExtends.get("concept"));
        if (targetPack == null || targetConcept == null) {
            return null;
        }
        return new ExtensionTarget(targetPack, targetConcept);
    }

    /**
     * Applies one extension pack's field contribution onto a base concept, additive-only.
     *
     * @param basePackAlias      the base pack's alias, named in refusal messages
     * @param basePackJson       the base pack's raw {@code pack.json}, used only to compute sealedness
     * @param baseConcept        the base pack's already-compiled concept being extended
     * @param extensionPackAlias the extension pack's alias, named in refusal messages
     * @param extensionConcept   the extension pack's own compiled concept (same bare concept name)
     * @return a new {@link CompiledConcept}, identical to {@code baseConcept} except for the extra
     *         additive fields folded in
     * @throws PackExtensionRefusedException if the base pack is sealed, or if any extension field
     *         collides with an existing field of a different shape, or declares a non-nullable field
     */
    public CompiledConcept applyExtension(
            String basePackAlias,
            JsonNode basePackJson,
            CompiledConcept baseConcept,
            String extensionPackAlias,
            CompiledConcept extensionConcept
    ) {
        Objects.requireNonNull(baseConcept, "baseConcept");
        Objects.requireNonNull(extensionConcept, "extensionConcept");

        PackSealednessAnalyzer.SealednessResult sealedness = PackSealednessAnalyzer.analyze(basePackJson);
        if (sealedness.sealed()) {
            throw new PackExtensionRefusedException(
                    "Refusing to extend pack '" + basePackAlias + "' from pack '" + extensionPackAlias
                            + "' -- '" + basePackAlias + "' is sealed (eligible to ship as a precompiled jar; "
                            + "see PackSealednessAnalyzer). A sealed pack is an immutable artifact -- patching "
                            + "one of its concepts in place is a contradiction. Use a satellite concept "
                            + "(satelliteOf) instead, or keep '" + basePackAlias + "' unsealed for as long as "
                            + "in-place extension of it must remain possible.");
        }

        Map<String, CompiledField> baseFieldsByName = new LinkedHashMap<>();
        for (CompiledField field : baseConcept.getFields()) {
            baseFieldsByName.put(field.getName(), field);
        }

        List<CompiledField> merged = new ArrayList<>(baseConcept.getFields());
        for (CompiledField candidate : extensionConcept.getFields()) {
            CompiledField existing = baseFieldsByName.get(candidate.getName());
            if (existing == null) {
                if (candidate.isRequired()) {
                    throw new PackExtensionRefusedException(
                            "Refusing extension of '" + baseConcept.getName() + "' by pack '" + extensionPackAlias
                                    + "' -- new field '" + candidate.getName() + "' is required (not nullable). "
                                    + "An additive extension must not introduce a required field: every app "
                                    + "already composing base pack '" + basePackAlias + "' has existing rows with "
                                    + "no value for it, which a required column would reject. Declare '"
                                    + candidate.getName() + "' as optional instead.");
                }
                merged.add(candidate);
                continue;
            }
            if (!sameShape(existing, candidate)) {
                throw new PackExtensionRefusedException(
                        "Refusing extension of '" + baseConcept.getName() + "' -- base pack '" + basePackAlias
                                + "' and extension pack '" + extensionPackAlias + "' both declare a member named '"
                                + candidate.getName() + "' with different shapes (base: type=" + existing.getDslType()
                                + ", required=" + existing.isRequired() + ", unique=" + existing.isUnique()
                                + "; extension: type=" + candidate.getDslType() + ", required=" + candidate.isRequired()
                                + ", unique=" + candidate.isUnique() + "). An in-place extension is additive-only -- "
                                + "it must never change or remove an existing field's type, nullability or name. "
                                + "Rename the extension's field, or drop it if it was meant to reference the base "
                                + "field of the same name.");
            }
            // Identical shape (typically the shared `id` anchor field the extension had to redeclare
            // to be a syntactically valid concept on its own) -- already present, nothing to merge.
        }

        return new CompiledConcept(
                baseConcept.getName(),
                baseConcept.getClassName(),
                baseConcept.getTableName(),
                List.copyOf(merged),
                baseConcept.getExpressionInvariants(),
                baseConcept.getInvariants(),
                baseConcept.getLifecycle(),
                baseConcept.getUi(),
                baseConcept.getTruthLevel(),
                baseConcept.getModule(),
                baseConcept.getIndexes(),
                baseConcept.getAccess(),
                baseConcept.getRenamedFrom(),
                baseConcept.getSatelliteOf(),
                baseConcept.getOrigin(),
                baseConcept.isSoftDelete()
        );
    }

    /**
     * Folds every {@link ExtensionSource} in {@code extensions} into {@code app}'s already-composed
     * concept list, replacing each extension's target concept with the additively-merged result. The
     * extension pack's OWN standalone compiled concept (e.g. {@code clinical::User}) never appears in
     * the returned model on its own -- it existed only to carry the patch, per this class's own
     * {@code metadata.extends} convention.
     *
     * @param basePackJsonByAlias the raw {@code pack.json} of every base pack an extension might
     *                            target, keyed by alias -- used only for the sealedness check
     * @throws IllegalArgumentException    if an extension source declares no {@code metadata.extends}
     *                                      target, or its target concept does not exist in {@code app}
     * @throws PackExtensionRefusedException per {@link #applyExtension}
     */
    public CompiledModel composeExtensions(
            CompiledModel app,
            Map<String, JsonNode> basePackJsonByAlias,
            List<ExtensionSource> extensions
    ) {
        Objects.requireNonNull(app, "app");
        LinkedHashMap<String, CompiledConcept> byName = new LinkedHashMap<>();
        for (CompiledConcept concept : app.getConcepts()) {
            byName.put(concept.getName(), concept);
        }
        if (extensions != null) {
            for (ExtensionSource source : extensions) {
                ExtensionTarget target = readExtensionTarget(source.extensionPackJson());
                if (target == null) {
                    throw new IllegalArgumentException(
                            "extension pack '" + source.extensionAlias() + "' declares no metadata.extends target");
                }
                String qualifiedTarget = target.qualifiedName();
                CompiledConcept base = byName.get(qualifiedTarget);
                if (base == null) {
                    throw new IllegalArgumentException(
                            "extension pack '" + source.extensionAlias() + "' targets '" + qualifiedTarget
                                    + "', which is not present in the composed model");
                }
                JsonNode basePackJson = basePackJsonByAlias == null ? null : basePackJsonByAlias.get(target.packAlias());
                CompiledConcept merged = applyExtension(
                        target.packAlias(), basePackJson, base, source.extensionAlias(), source.extensionConcept());
                byName.put(qualifiedTarget, merged);
            }
        }

        return new CompiledModel(
                app.getNamespace(),
                app.getDslVersion(),
                app.getVersion(),
                byName,
                app.getDomainTypes(),
                app.getCapabilities(),
                app.getBindings(),
                app.getEvents(),
                app.getFlows(),
                app.getOrchestrationRules(),
                app.getQueries(),
                app.getRuleProfiles(),
                app.getProcedures(),
                app.getPanels(),
                app.getGuidePages(),
                app.getAggregates(),
                app.getAutoPanels(),
                app.getDocuments(),
                app.getExternalAi(),
                app.getSettings(),
                app.getRoles(),
                app.getPropertyScopes(),
                app.getProperties(),
                app.getContexts(),
                app.getConversions()
        );
    }

    /**
     * PACK-10 step 4 result: the extension-composed model (identical to what {@link
     * #composeExtensions} would produce, except each extended concept's field order additionally
     * honors the app's own {@code metadata.fieldOrder} directive -- see {@link
     * #composeExtensionsWithOrdering}), plus per-concept, per-field provenance for the fields an
     * extension pack actually ADDED (a pre-existing field an extension merely re-declares to stay a
     * syntactically valid concept, e.g. the shared {@code id} anchor, carries no provenance entry --
     * it was not added, so there is nothing to attribute).
     *
     * @param extensionFieldOrigins pack-qualified concept name (e.g. {@code "clinicbase::Patient"})
     *                              to a map of {@code fieldName -> extensionPackAlias}, for UI
     *                              attribution (e.g. a small "+clinicext" badge next to the field).
     *                              A concept with no extension-added fields is simply absent as a key
     *                              (never present with an empty map).
     */
    public record ExtensionComposition(CompiledModel model, Map<String, Map<String, String>> extensionFieldOrigins) {
    }

    /**
     * PACK-10 step 4: {@link #composeExtensions}'s merge, plus the two things pure field-shape
     * composition alone cannot give a UI renderer -- who added a field, and where the APP (not the
     * extension) wants it to render. <b>Deliberately the extension pack's own field declaration
     * order never determines final position</b> -- see this class's own doc: an extension able to
     * dictate its position in every consuming app's forms would let two extensions fight over the
     * same slot, with the app author holding no recourse. Final order is either (a) the base pack's
     * original declaration order with each extension's added fields appended, in the order {@code
     * extensions} was processed -- the sane default when the app declares nothing -- or (b) the
     * app's own explicit {@code metadata.fieldOrder} directive (see {@link
     * #readFieldOrderOverrides}), applied AFTER every extension has folded in so an app ordering two
     * extensions' fields relative to each other sees both. A field the app's order list omits (e.g.
     * one a newer extension just added that the app's model.json has not caught up to yet) is never
     * dropped -- it is appended after every named field, in its pre-reorder position, so a forgotten
     * field stays visible rather than silently vanishing.
     *
     * @param appModelJson the app's own root model.json, read ONLY for {@code metadata.fieldOrder}
     *                     (may be {@code null}, equivalent to the app declaring no directive at all)
     */
    public ExtensionComposition composeExtensionsWithOrdering(
            CompiledModel app,
            Map<String, JsonNode> basePackJsonByAlias,
            List<ExtensionSource> extensions,
            JsonNode appModelJson
    ) {
        Objects.requireNonNull(app, "app");
        Map<String, List<String>> fieldOrderOverrides = readFieldOrderOverrides(appModelJson);

        LinkedHashMap<String, CompiledConcept> byName = new LinkedHashMap<>();
        for (CompiledConcept concept : app.getConcepts()) {
            byName.put(concept.getName(), concept);
        }
        Map<String, Map<String, String>> originsByConcept = new LinkedHashMap<>();

        if (extensions != null) {
            for (ExtensionSource source : extensions) {
                ExtensionTarget target = readExtensionTarget(source.extensionPackJson());
                if (target == null) {
                    throw new IllegalArgumentException(
                            "extension pack '" + source.extensionAlias() + "' declares no metadata.extends target");
                }
                String qualifiedTarget = target.qualifiedName();
                CompiledConcept base = byName.get(qualifiedTarget);
                if (base == null) {
                    throw new IllegalArgumentException(
                            "extension pack '" + source.extensionAlias() + "' targets '" + qualifiedTarget
                                    + "', which is not present in the composed model");
                }
                JsonNode basePackJson = basePackJsonByAlias == null ? null : basePackJsonByAlias.get(target.packAlias());
                CompiledConcept merged = applyExtension(
                        target.packAlias(), basePackJson, base, source.extensionAlias(), source.extensionConcept());

                Map<String, String> addedOrigins = newlyAddedFieldNames(base, merged, source.extensionAlias());
                if (!addedOrigins.isEmpty()) {
                    originsByConcept.computeIfAbsent(qualifiedTarget, key -> new LinkedHashMap<>()).putAll(addedOrigins);
                }
                byName.put(qualifiedTarget, merged);
            }
        }

        LinkedHashMap<String, CompiledConcept> orderedByName = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledConcept> entry : byName.entrySet()) {
            CompiledConcept concept = entry.getValue();
            List<String> order = fieldOrderOverrides.get(entry.getKey());
            if (order == null || order.isEmpty()) {
                orderedByName.put(entry.getKey(), concept);
                continue;
            }
            orderedByName.put(entry.getKey(), withFields(concept, reorderFields(concept.getFields(), order)));
        }

        CompiledModel model = new CompiledModel(
                app.getNamespace(),
                app.getDslVersion(),
                app.getVersion(),
                orderedByName,
                app.getDomainTypes(),
                app.getCapabilities(),
                app.getBindings(),
                app.getEvents(),
                app.getFlows(),
                app.getOrchestrationRules(),
                app.getQueries(),
                app.getRuleProfiles(),
                app.getProcedures(),
                app.getPanels(),
                app.getGuidePages(),
                app.getAggregates(),
                app.getAutoPanels(),
                app.getDocuments(),
                app.getExternalAi(),
                app.getSettings(),
                app.getRoles(),
                app.getPropertyScopes(),
                app.getProperties(),
                app.getContexts(),
                app.getConversions()
        );

        return new ExtensionComposition(model, originsByConcept);
    }

    /**
     * PACK-10 step 4: reads the app's own field-order directive from {@code metadata.fieldOrder} on
     * its root model.json -- keyed by pack-qualified concept name (e.g. {@code
     * "clinicbase::Patient"}) to an ordered array of field names. Uses the SAME schema-free trick
     * {@link #readExtensionTarget} already established for {@code metadata.extends}: {@code
     * model.schema.json}'s root {@code metadata} property is already an untyped {@code
     * {"type":"object"}}, so declaring this requires no schema change in either mirror. Returns an
     * empty map (never {@code null}) when the app declares no directive at all, or a malformed one
     * (non-object {@code metadata}/{@code fieldOrder}, or a non-array value for a concept) -- silently
     * falling back to the default order rather than failing generation over presentation metadata.
     */
    public Map<String, List<String>> readFieldOrderOverrides(JsonNode appModelJson) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (appModelJson == null || !appModelJson.isObject()) {
            return result;
        }
        JsonNode metadata = appModelJson.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            return result;
        }
        JsonNode fieldOrder = metadata.get("fieldOrder");
        if (fieldOrder == null || !fieldOrder.isObject()) {
            return result;
        }
        fieldOrder.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isArray()) {
                return;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode item : value) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    names.add(item.asText());
                }
            }
            if (!names.isEmpty()) {
                result.put(entry.getKey(), names);
            }
        });
        return result;
    }

    /**
     * Reorders {@code fields} per the app's declared order for one concept: every named field is
     * moved to the EXACT position the app listed it in, then any field the app's list did not
     * mention is appended afterward in its original (pre-reorder) relative order -- so a field a
     * newer extension adds, that the app's {@code metadata.fieldOrder} has not caught up to yet,
     * still renders (at the end) rather than silently vanishing. An unknown name in {@code order}
     * (a typo, or a field from an extension that was never applied) is simply ignored.
     */
    private static List<CompiledField> reorderFields(List<CompiledField> fields, List<String> order) {
        Map<String, CompiledField> byName = new LinkedHashMap<>();
        for (CompiledField field : fields) {
            byName.put(field.getName(), field);
        }
        List<CompiledField> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (String name : order) {
            CompiledField field = byName.get(name);
            if (field != null && placed.add(name)) {
                ordered.add(field);
            }
        }
        for (CompiledField field : fields) {
            if (placed.add(field.getName())) {
                ordered.add(field);
            }
        }
        return ordered;
    }

    /** The field names present in {@code merged} but absent from {@code base} -- i.e. the fields
     *  THIS extension actually added (never one it merely re-declared identically, like the shared
     *  {@code id} anchor) -- each attributed to {@code extensionPackAlias} for UI provenance. */
    private static Map<String, String> newlyAddedFieldNames(CompiledConcept base, CompiledConcept merged, String extensionPackAlias) {
        Set<String> baseNames = new LinkedHashSet<>();
        for (CompiledField field : base.getFields()) {
            baseNames.add(field.getName());
        }
        Map<String, String> origins = new LinkedHashMap<>();
        for (CompiledField field : merged.getFields()) {
            if (!baseNames.contains(field.getName())) {
                origins.put(field.getName(), extensionPackAlias);
            }
        }
        return origins;
    }

    /** Rebuilds {@code concept} with a different field list, every other property carried over
     *  unchanged -- used only to apply {@link #reorderFields}'s result. */
    private static CompiledConcept withFields(CompiledConcept concept, List<CompiledField> fields) {
        return new CompiledConcept(
                concept.getName(),
                concept.getClassName(),
                concept.getTableName(),
                List.copyOf(fields),
                concept.getExpressionInvariants(),
                concept.getInvariants(),
                concept.getLifecycle(),
                concept.getUi(),
                concept.getTruthLevel(),
                concept.getModule(),
                concept.getIndexes(),
                concept.getAccess(),
                concept.getRenamedFrom(),
                concept.getSatelliteOf(),
                concept.getOrigin(),
                concept.isSoftDelete()
        );
    }

    /** True when two same-named fields are structurally interchangeable -- see the class doc's
     *  additive-only contract. Deliberately narrower than full equality (ui/picker/file metadata
     *  differences are cosmetic, not a data-shape break) but strict on everything that determines
     *  the physical column/validation: dsl type, id-ness, required-ness, uniqueness, reference target
     *  and domain type. */
    private static boolean sameShape(CompiledField a, CompiledField b) {
        return Objects.equals(a.getDslType(), b.getDslType())
                && a.isId() == b.isId()
                && a.isRequired() == b.isRequired()
                && a.isUnique() == b.isUnique()
                && Objects.equals(a.getReferenceTarget(), b.getReferenceTarget())
                && Objects.equals(a.getDomainType(), b.getDomainType());
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }
}
