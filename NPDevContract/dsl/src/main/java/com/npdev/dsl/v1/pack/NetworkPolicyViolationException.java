package com.npdev.dsl.v1.pack;

import java.io.IOException;

/**
 * PK-5 step 2: thrown by {@link NetworkPolicy#requireAllowed(String)} when code on a hermetic path
 * (today: real {@code npdev generate}/{@code validate}, via {@code PackDependencyGraphWalker}'s
 * hardcoded {@link NetworkPolicy#DENIED}) attempts an operation that would touch the network.
 *
 * <p>Extends {@link IOException} (not a plain {@code RuntimeException}) so it propagates through
 * every existing pack-resolution call site unchanged -- they already declare {@code throws
 * IOException} and catch it into a CLI JSON report (see {@code PackAddMain}/{@code
 * ModelValidatorMain}) -- while remaining a distinct, {@code assertThrows}-able type so a test can
 * tell "the guard fired" apart from "the fetch itself failed" (a real connection failure surfaces as
 * a plain {@code IOException} from the git process or HTTP client, never this type).
 */
public final class NetworkPolicyViolationException extends IOException {
    public NetworkPolicyViolationException(String message) {
        super(message);
    }
}
