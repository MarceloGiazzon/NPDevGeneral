package com.npdev.generator.packs;

import com.npdev.kernel.abi.KernelAbi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BT-2 "Breaks": "A pack jar must declare the kernel ABI it was built against and refuse to link
 * against an incompatible one." Live proof of both halves of that sentence.
 */
class PackAbiCompatibilityTest {

    @Test
    void matchingAbi_isLinkable() {
        PackAbiManifest manifest = new PackAbiManifest("identity", "1.0.0", 1, KernelAbi.CURRENT_ABI_VERSION);

        assertDoesNotThrow(() -> PackAbiCompatibility.checkLinkable(manifest));
    }

    @Test
    void mismatchedAbi_refusesToLink_namingBothVersions() {
        PackAbiManifest manifest = new PackAbiManifest("identity", "1.0.0", 1, "1");

        PackAbiIncompatibleException thrown = assertThrows(PackAbiIncompatibleException.class,
                () -> PackAbiCompatibility.checkLinkable(manifest, "2"));

        assertTrue(thrown.getMessage().contains("identity"));
        assertTrue(thrown.getMessage().contains("'1'"));
        assertTrue(thrown.getMessage().contains("'2'"));
    }
}
