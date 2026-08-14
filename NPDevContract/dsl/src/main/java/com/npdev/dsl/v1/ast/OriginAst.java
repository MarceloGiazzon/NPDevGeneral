package com.npdev.dsl.v1.ast;

/**
 * PACK-2 (ledger; {@code PACK-ROADMAP.md} card PK-1 steps 5-7): pack-attribution provenance for a
 * single compiled member (concept, query, event, flow, capability, role, panel, or domain type) --
 * which pack declared it, at which version, over what content, and whether that pack is sealed.
 *
 * <p>{@code null} on the owning AST node means "not pack-contributed" (an app's own root- or
 * context-declared member) -- the same null-means-absent convention {@code satelliteOf}/
 * {@code renamedFrom} already use elsewhere in this package, rather than an all-null instance of
 * this record. A pack-contributed member always carries a non-null {@link #packId()}; the other
 * three fields may individually be blank/false when the resolver could not establish them (see
 * each field's own doc).
 *
 * @param packId      the pack's own declared {@code pack} identifier (never the local {@code as}
 *                    alias a model chose to import it under -- same "real id, not alias" rule PK-2
 *                    (the roadmap card)'s physical-qualifier derivation already established).
 * @param packVersion the pack's own declared {@code version} string (e.g. {@code "1.0.0"}), verbatim.
 * @param packDigest  {@code sha256:<hex>} of the pack's own canonical bytes (local packs: the exact
 *                    value {@link com.npdev.dsl.v1.pack.PackLockFile#sha256} computes over the pack
 *                    file; remote packs: the {@link com.npdev.dsl.v1.pack.PackCache} entry digest) --
 *                    reuses the digest scheme {@code npdev.lock} already carries (PK-5), rather than
 *                    inventing a second one.
 * @param sealed      whether {@link com.npdev.dsl.v1.pack.PackSealednessAnalyzer} certifies this
 *                    pack sealed (BT-2's existing notion, computed the same way here -- not a second,
 *                    divergent definition of "sealed").
 */
public record OriginAst(String packId, String packVersion, String packDigest, boolean sealed) {
}
