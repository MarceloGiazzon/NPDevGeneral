package com.npdev.generator.packs;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.pack.PackSealednessAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * Reads this pack's own declared extension target from {@code metadata.extends}, or {@code null}
     * if this pack declares none (an ordinary, non-extending pack).
     */
    public ExtensionTarget readExtensionTarget(JsonNode packJson) {
        if (packJson == null || !packJson.isObject()) {
            return null;
        }
        JsonNode metadata = packJson.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode extends_ = metadata.get("extends");
        if (extends_ == null || !extends_.isObject()) {
            return null;
        }
        String targetPack = textOrNull(extends_.get("pack"));
        String targetConcept = textOrNull(extends_.get("concept"));
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
