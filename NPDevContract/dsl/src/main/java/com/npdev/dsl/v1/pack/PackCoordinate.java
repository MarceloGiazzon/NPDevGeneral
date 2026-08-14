package com.npdev.dsl.v1.pack;

/**
 * PK-5 step 3 ("Distribution", {@code PACK-ROADMAP.md} card PK-5): a remote pack's location, as
 * declared by a model's {@code packs[].from} field -- the NEW field the card requires, kept
 * strictly separate from {@code packs[].$ref} (which stays local-file-only; see {@code
 * pack.schema.json}/{@code model.schema.json}'s {@code $ref} pattern, unchanged by this feature).
 *
 * <p>Two schemes, matching decision PD6 ({@code PACK-ROADMAP.md} table): {@link OciCoordinate}
 * ({@code oci://registry/repository(:tag|@sha256:digest)}) and {@link GitCoordinate} ({@code
 * git+<transport>://<repo-url>[//<subpath>]@<tag>}). Parsing here is pure and side-effect-free --
 * no filesystem, no network -- so it is fully unit-testable without a live fetch, per the card's own
 * "step 3 ... is fully testable without a live fetch" scoping note.
 */
public sealed interface PackCoordinate permits OciCoordinate, GitCoordinate {

    /** The original, unparsed {@code from} string, preserved verbatim -- this is also the exact
     *  key {@code npdev.lock} entries are matched against when the generate path (network-denied)
     *  looks up a previously-fetched pack; see {@code PackDependencyGraphWalker}. */
    String raw();

    static PackCoordinate parse(String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("pack coordinate must be a non-blank string");
        }
        String trimmed = from.trim();
        if (trimmed.startsWith("oci://")) {
            return OciCoordinate.parse(trimmed);
        }
        if (trimmed.startsWith("git+")) {
            return GitCoordinate.parse(trimmed);
        }
        throw new IllegalArgumentException("pack coordinate must start with 'oci://' or 'git+<transport>://': " + from);
    }
}
