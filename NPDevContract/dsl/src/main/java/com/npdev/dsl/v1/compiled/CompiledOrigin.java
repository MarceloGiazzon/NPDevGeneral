package com.npdev.dsl.v1.compiled;

/**
 * PACK-2 (ledger; {@code PACK-ROADMAP.md} card PK-1 steps 5-7): the compiled form of {@link
 * com.npdev.dsl.v1.ast.OriginAst} -- pack-attribution provenance carried on every {@code Compiled*}
 * member type this card covers ({@link CompiledConcept}, {@link CompiledQuery}, {@link
 * CompiledEvent}, {@link CompiledFlow}, {@link CompiledCapability}, {@link CompiledRole}, {@link
 * CompiledPanel}, {@link CompiledDomainType}), so a generated app's tables/members can be attributed
 * back to the pack that declared them and diffed pack-version-to-pack-version.
 *
 * <p>{@code null} on the owning member means "not pack-contributed" (an app's own root- or
 * context-declared member), mirroring how {@code renamedFrom}/{@code satelliteOf} already use
 * {@code null}-means-absent on {@link CompiledConcept} rather than an all-null sentinel instance.
 *
 * @param packId      the pack's own real identifier (never the app's local {@code as} alias).
 * @param packVersion the pack's own declared {@code version} string, verbatim.
 * @param packDigest  {@code sha256:<hex>} of the pack's own canonical bytes (same scheme {@code
 *                    npdev.lock}/PK-5 already use); may be blank if the resolver could not compute
 *                    one for this pack.
 * @param sealed      whether {@link com.npdev.dsl.v1.pack.PackSealednessAnalyzer} certifies this
 *                    pack sealed at resolution time.
 */
public record CompiledOrigin(String packId, String packVersion, String packDigest, boolean sealed) {
}
