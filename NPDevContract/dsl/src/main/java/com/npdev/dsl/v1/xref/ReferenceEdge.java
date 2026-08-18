package com.npdev.dsl.v1.xref;

import java.util.Objects;

/**
 * One reference from a model object to another: "this panel's third field binding names the field
 * {@code WidgetOrder.lineCount}".
 *
 * @param fromKind kind of the owning object -- {@code panel}, {@code query}, {@code procedure},
 *                 {@code flow}, {@code aggregate}, {@code concept}, {@code autoPanel},
 *                 {@code selector}, {@code guidePage}, {@code document}, {@code conversion}.
 * @param fromName qualified name of the owning object, exactly as the composed model spells it
 *                 (so a pack-contributed panel reads {@code labeling::LabelPanel}).
 * @param site     the stable, dotted reference-BEARING key, independent of which instance carries
 *                 it -- {@code panel.fieldBindings.field}, {@code query.orderBy}. This is what
 *                 lets a consumer reason about a CLASS of reference (REG-185 uses it to skip the
 *                 sites other validators already cover, so one mistake never produces two errors).
 * @param path     the structural pointer to this exact occurrence --
 *                 {@code panels[WidgetOrderReviewPanel].fieldBindings[2].field}. XREF-3's
 *                 {@code --cascade} edits AT this pointer rather than string-replacing across the
 *                 file, which is the difference between a rename and a corruption.
 * @param toKind   kind of the target -- {@code field}, {@code concept}, {@code procedure},
 *                 {@code flow}, {@code query}, {@code event}, {@code capability},
 *                 {@code aggregate}, {@code guidePage}, {@code dataSource}, {@code domainType},
 *                 {@code selector}, {@code panel}, {@code invariant}.
 * @param toName   for {@code field} targets, {@code Concept.field} (qualified concept included);
 *                 for everything else, the bare target name. For an
 *                 {@link Resolution#UNDECIDABLE} edge this is the RAW text that could not be
 *                 evaluated, so a human can still read what was written.
 * @param ownerConcept the concept a {@code field} target belongs to, or null for other kinds --
 *                 carried separately so a consumer never has to re-split {@code toName} on a dot
 *                 (concept names may themselves contain {@code ::} but never {@code .}).
 * @param resolution see {@link Resolution}; never null.
 */
public record ReferenceEdge(
        String fromKind,
        String fromName,
        String site,
        String path,
        String toKind,
        String toName,
        String ownerConcept,
        Resolution resolution
) implements Comparable<ReferenceEdge> {

    public ReferenceEdge {
        fromKind = safe(fromKind);
        fromName = safe(fromName);
        site = safe(site);
        path = safe(path);
        toKind = safe(toKind);
        toName = safe(toName);
        Objects.requireNonNull(resolution, "resolution");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** True when this edge points at {@code Concept.field} (or, with a null field, the concept). */
    public boolean targetsField() {
        return "field".equals(toKind);
    }

    /**
     * Total order used for EMISSION, not for meaning. {@code check-deterministic-generation.ps1}
     * SHA-256s every generated file across two runs, so an unstable iteration order here would
     * fail the generator gate rather than merely producing a noisy diff.
     */
    @Override
    public int compareTo(ReferenceEdge other) {
        int result = path.compareTo(other.path);
        if (result != 0) {
            return result;
        }
        result = toKind.compareTo(other.toKind);
        return result != 0 ? result : toName.compareTo(other.toName);
    }
}
