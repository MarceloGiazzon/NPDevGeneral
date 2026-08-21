package com.npdev.generator.packs;

import com.npdev.dsl.v1.compiled.JavaIdentifierSupport;

import java.util.List;
import java.util.Optional;

/**
 * BUILD-2 (REST-layer follow-on, ledger item BUILD-2): identifies one pack a generated app links as
 * a precompiled sealed jar (see {@link SealedPackJarBuilder}) instead of generating that pack's own
 * entity/repository sources -- {@code GeneratorFacade}'s {@code linkedSealedPacks} parameter is a
 * list of these, and the REST-layer emitters ({@code ServiceEmitter}/{@code ControllerEmitter}) use
 * {@link #resolve} to find, for a given concept, the REAL package/class the sealed jar actually
 * contains -- instead of the app's own flat {@code com.npdev.generated.entities} default.
 *
 * @param alias    the {@code BuiltinPackComposer}-style local alias prefix THIS app composed the
 *                 pack under (e.g. {@code "identity"}, matching {@code identity::User}-style
 *                 concept names in the composed model) -- may differ from {@code manifest.packId()}
 *                 if the app links the pack under an {@code as:} alias; every real caller today
 *                 uses {@code alias == packId}.
 * @param manifest the sealed pack's own ABI manifest (packId/packVersion/packMajorVersion/
 *                 kernelAbiVersion) -- {@link PackAbiManifest#packageName()} is the jar's real Java
 *                 namespace ({@code com.npdev.pack.<packId>.v<major>}) that a linked concept's
 *                 REST-layer classes must import from.
 */
public record LinkedSealedPack(String alias, PackAbiManifest manifest) {

    public LinkedSealedPack {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
    }

    /** {@code "<alias>::"} -- the same qualified-name prefix {@code BuiltinPackComposer} gives every
     *  concept this pack contributes to the composed model. */
    public String conceptPrefix() {
        return alias + "::";
    }

    /**
     * Bare (alias-independent) Java class name for a concept qualified under THIS link's alias --
     * e.g. {@code "identity::User"} under alias {@code "identity"} becomes {@code "User"}. Mirrors,
     * exactly, the same bare-naming {@code SealedPackBuilder.seal()} gives the identical concept
     * when it seals the pack itself -- the two computations must never drift, or a linked app's
     * REST-layer classes would import a class name the sealed jar does not actually contain.
     */
    public String bareClassName(String qualifiedConceptName) {
        String prefix = conceptPrefix();
        String bare = qualifiedConceptName != null && qualifiedConceptName.startsWith(prefix)
                ? qualifiedConceptName.substring(prefix.length())
                : qualifiedConceptName;
        return JavaIdentifierSupport.className(bare);
    }

    /** One concept's resolved linkage: the sealed jar's own package, and the bare class name inside it. */
    public record ConceptLinkage(String entityPackage, String entityTypeName) {
    }

    /**
     * Resolves {@code qualifiedConceptName} against every link in {@code linkedSealedPacks},
     * returning empty when the concept belongs to none of them (an app-owned concept, or a
     * composed-but-not-linked pack) -- callers fall back to the app's own default entity
     * package/class name in that case, unchanged from every pre-BUILD-2 caller.
     */
    public static Optional<ConceptLinkage> resolve(String qualifiedConceptName, List<LinkedSealedPack> linkedSealedPacks) {
        if (qualifiedConceptName == null || linkedSealedPacks == null || linkedSealedPacks.isEmpty()) {
            return Optional.empty();
        }
        for (LinkedSealedPack link : linkedSealedPacks) {
            if (qualifiedConceptName.startsWith(link.conceptPrefix())) {
                return Optional.of(new ConceptLinkage(link.manifest().packageName(), link.bareClassName(qualifiedConceptName)));
            }
        }
        return Optional.empty();
    }
}
