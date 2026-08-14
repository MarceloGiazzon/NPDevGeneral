package com.npdev.generator.packs;

import com.npdev.kernel.abi.KernelAbi;

/**
 * BT-2 (PACK-ROADMAP.md, Track B): "A pack jar must declare the kernel ABI it was built against and
 * refuse to link against an incompatible one" -- this is that refusal, a pure comparison against the
 * linking side's current {@link KernelAbi#CURRENT_ABI_VERSION}.
 *
 * <p>Deliberately a single equality check today, not a compatibility RANGE (e.g. "any ABI &lt;= mine
 * is fine") -- {@link KernelAbi}'s own doc explains why: the version is a manually-bumped contract
 * number, not a diffed set of actually-used signatures, so this class has no way to know whether a
 * given past ABI version is safe against the current kernel beyond "was it the exact version this jar
 * was built against". Widening this to a real range requires the ABI-diff tooling {@link KernelAbi}
 * already documents as deferred.
 */
public final class PackAbiCompatibility {

    private PackAbiCompatibility() {
    }

    /**
     * @throws PackAbiIncompatibleException naming both the manifest's declared ABI version and the
     *                                       current one, when they differ
     */
    public static void checkLinkable(PackAbiManifest manifest) {
        checkLinkable(manifest, KernelAbi.CURRENT_ABI_VERSION);
    }

    /** Overload taking the runtime ABI explicitly -- used by tests to exercise a mismatch without
     *  needing a second, fake kernel version to exist anywhere. */
    public static void checkLinkable(PackAbiManifest manifest, String currentKernelAbiVersion) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        if (!manifest.kernelAbiVersion().equals(currentKernelAbiVersion)) {
            throw new PackAbiIncompatibleException(
                    "sealed pack '" + manifest.packId() + "' (version " + manifest.packVersion()
                            + ") was built against kernel ABI '" + manifest.kernelAbiVersion()
                            + "', but this kernel is ABI '" + currentKernelAbiVersion + "' -- refusing to link. "
                            + "Re-seal the pack against the current kernel, or link a kernel matching ABI '"
                            + manifest.kernelAbiVersion() + "'.");
        }
    }
}
