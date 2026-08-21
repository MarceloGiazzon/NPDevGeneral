package com.npdev.generator.packs;

/**
 * BUILD-2 (BT-2 follow-on, "the linking"): thrown by {@link SealedPackJarBuilder} when compiling a
 * sealed pack's staged Java sources into {@code .class} bytes fails -- most commonly because the
 * calling JVM's own classpath does not carry a dependency the pack's emitted entities need (e.g.
 * {@code jakarta.persistence-api}, currently only a {@code testImplementation} dependency of
 * {@code :generator} -- see {@code SealedPackJarBuilder}'s own class doc for why that is a real,
 * named limitation of the PRODUCTION path rather than a silent gap). Carries the raw javac
 * diagnostics so the failure is actionable rather than a bare non-zero exit code.
 */
public final class PackJarCompilationException extends RuntimeException {

    public PackJarCompilationException(String message) {
        super(message);
    }
}
