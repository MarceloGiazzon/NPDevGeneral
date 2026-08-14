package com.npdev.kernel.abi;

/**
 * BT-2 (PACK-ROADMAP.md, Track B): the ABI contract version for the kernel/runtime-support surface a
 * sealed pack jar's generated code is allowed to call into -- {@code com.npdev.kernel.*} and
 * {@code com.npdev.runtime.support.*} (the same "stable platform types" {@code service-base.mustache}
 * already restricts itself to, per the card's own "Breaks" section).
 *
 * <p><b>Why this exists.</b> A sealed pack ships as a jar built ONCE, ahead of time, against whatever
 * kernel version happened to be current at build time. Every app that later links that jar brings its
 * own (possibly newer, possibly older) kernel to the table. If the kernel surface the pack's generated
 * code calls into has changed shape since the jar was built -- a renamed method, a changed signature
 * on {@code GeneratedCrudRuntimeSupport} (3,701 lines, {@code NPDevKernel/adapters/expression-cel},
 * treated as ABI by this mechanism) -- linking the old jar against the new kernel is a
 * {@code NoSuchMethodError} waiting to happen at runtime, not at build time. This constant, and
 * {@code PackAbiManifest}/{@code PackAbiCompatibility} in the generator module, exist so that
 * mismatch is instead a named, build-time refusal.
 *
 * <p><b>What this is NOT (an honest limitation, not a silent gap).</b> This is a manually-bumped
 * contract number, not automatic bytecode-signature diffing of the kernel's actual public surface.
 * Bumping it is a human decision made at the point a change to {@code com.npdev.kernel.*} /
 * {@code com.npdev.runtime.support.*} / {@code GeneratedCrudRuntimeSupport}'s public signatures would
 * break an already-built sealed-pack jar. A real ABI-diff tool (comparing the actual method/field
 * signatures a sealed pack's generated code references against what the linked kernel version
 * actually exports) is materially harder and explicitly out of scope for this slice -- see BT-2's
 * ledger item for the deferral. Until that exists, review discipline is what keeps this constant
 * honest, the same way {@code check-rollback-claims.py} keeps migration-rollback prose honest by
 * convention rather than by proving the SQL.
 *
 * <p>Single source of truth: read directly by {@code com.npdev.generator.packs.PackAbiCompatibility}
 * (generator module already depends on {@code :kernel}, see {@code NPDevGenerator/generator/build.gradle}),
 * and written into every sealed pack jar's {@code META-INF/npdev-pack.properties} manifest at
 * pack-seal time by {@code com.npdev.generator.packs.SealedPackBuilder}. Never hand-copy this value
 * anywhere else -- read this constant, the same discipline CLAUDE.md's twin-pair rule exists to
 * enforce for every other "one place updated, its twin forgotten" hazard in this repo.
 */
public final class KernelAbi {

    /**
     * Bump this ONLY when a change to the kernel/runtime-support surface a sealed pack's generated
     * code calls into ({@code com.npdev.kernel.*}, {@code com.npdev.runtime.support.*}, especially
     * {@code GeneratedCrudRuntimeSupport}'s public signatures) would break an already-built sealed
     * pack jar. An additive, backward-compatible kernel change does not require a bump.
     */
    public static final String CURRENT_ABI_VERSION = "1";

    private KernelAbi() {
    }
}
