package com.npdev.dsl.v1.pack;

/**
 * PK-4 Stage B: the size of a version bump between two {@code major.minor.patch} pack versions, or
 * the size of bump a classified pack diff requires -- the same four-value scale is used for both
 * sides of {@link PackPublishGate}'s comparison.
 *
 * <p>Declared in ascending severity ({@link #NONE} &lt; {@link #PATCH} &lt; {@link #MINOR} &lt;
 * {@link #MAJOR}) so the gate's "is the actual bump at least as big as the required one" check is
 * exactly {@code actual.ordinal() >= required.ordinal()} (see {@link PackPublishGate#evaluate}).
 */
public enum PackVersionBump {
    /** No version change at all (old and new versions are identical). */
    NONE,
    PATCH,
    MINOR,
    MAJOR;

    /**
     * The minimum bump size {@link PackChangeClassification} requires: BREAKING needs at least a
     * major bump, ADDITIVE needs at least a minor bump (a major bump also satisfies it -- semver's
     * usual "bigger also counts" rule), and PATCH needs at least a patch bump (minor/major also
     * satisfy it, they are just not REQUIRED). There is no classification that maps to {@link #NONE}
     * here -- an actual code difference always requires at least a patch bump; {@link #NONE} is only
     * ever the REQUIRED value when there was no diff at all (see {@link
     * PackPublishGate#requiredBump}, which handles the empty-findings case separately).
     */
    static PackVersionBump requiredFor(PackChangeClassification classification) {
        return switch (classification) {
            case BREAKING -> MAJOR;
            case ADDITIVE -> MINOR;
            case PATCH -> PATCH;
        };
    }
}
