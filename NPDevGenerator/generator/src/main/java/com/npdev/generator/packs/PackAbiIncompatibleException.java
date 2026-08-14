package com.npdev.generator.packs;

/**
 * BT-2: thrown by {@link PackAbiCompatibility} when a sealed pack jar's declared
 * {@link PackAbiManifest#kernelAbiVersion()} does not match the linking side's current
 * {@code com.npdev.kernel.abi.KernelAbi.CURRENT_ABI_VERSION} -- a real, named refusal rather than a
 * runtime {@code NoSuchMethodError} discovered later, deep inside a generated app.
 */
public final class PackAbiIncompatibleException extends RuntimeException {

    public PackAbiIncompatibleException(String message) {
        super(message);
    }
}
