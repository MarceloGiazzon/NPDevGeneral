package com.npdev.generator.packs;

/**
 * PACK-10 steps 2/3: thrown by {@link PackExtensionComposer} when an extension pack's attempt to
 * additively patch a field onto a base pack's own concept must be refused -- either because the base
 * pack is sealed (an immutable artifact; patching one is a contradiction) or because the extension
 * collides with an existing member of the base concept (a same-named field with a different shape,
 * which would silently change the meaning of data every app already composing the base pack has).
 *
 * <p>Always names BOTH packs (the base and the extension) and the specific colliding member, so the
 * refusal is actionable rather than a bare "no" -- the same discipline {@link PackNotSealedException}
 * already established for BT-2's sealing refusal.
 */
public final class PackExtensionRefusedException extends RuntimeException {

    public PackExtensionRefusedException(String message) {
        super(message);
    }
}
