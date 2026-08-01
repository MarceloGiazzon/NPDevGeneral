package com.npdev.adapters.runtime.validation;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Wave 1.3 (LC-C2, MASTER_AI_PLATFORM_PROGRAMME_v2.md): a real entry point for re-signing an app's
 * {@code npdev-generated/} tree after a SANCTIONED out-of-band write -- today, only the fast-path
 * script writing a new {@code compiled-model.json} after {@code ModelChangeClassifierMain} has
 * itself refused anything but a {@code METADATA_ONLY} change.
 *
 * <p>{@link StrictExecutionValidator} refuses to boot when {@link GeneratedFolderSignature#capture}
 * (recomputed live at every boot) disagrees with the signature file written at generation time --
 * exactly the guard that caught the FIRST version of this fast path, which overwrote
 * {@code compiled-model.json} without updating the signature (reproduced live:
 * {@code StrictExecutionViolationException: ... content mismatch for
 * src/main/resources/npdev/compiled-model.json}). This class calls the SAME
 * {@code capture}/{@code write} the generator itself uses (package-private, same package -- no
 * algorithm re-derivation, no second hash implementation to drift from the one the validator
 * actually checks) so the fast path re-signs with the identical tree-hash contract instead of
 * disabling or working around the guard.
 *
 * <p>Usage: {@code --generatedRoot <path to npdev-generated>}.
 */
public final class GeneratedFolderSignatureMain {

    private GeneratedFolderSignatureMain() {
    }

    public static void main(String[] args) throws IOException {
        String generatedRootArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--generatedRoot".equals(args[i])) {
                generatedRootArg = args[++i];
            } else {
                throw new IllegalArgumentException("Unrecognized argument: " + args[i] + " (supported: --generatedRoot)");
            }
        }
        if (generatedRootArg == null || generatedRootArg.isBlank()) {
            throw new IllegalArgumentException("--generatedRoot is required");
        }

        Path generatedRoot = Path.of(generatedRootArg).toAbsolutePath().normalize();
        GeneratedFolderSignature signature = GeneratedFolderSignature.capture(generatedRoot);
        Path signaturePath = generatedRoot.resolve(GeneratedFolderSignature.SIGNATURE_RELATIVE_PATH);
        signature.write(signaturePath);
        System.out.println("Re-signed " + generatedRoot + " -> " + signaturePath);
    }
}
