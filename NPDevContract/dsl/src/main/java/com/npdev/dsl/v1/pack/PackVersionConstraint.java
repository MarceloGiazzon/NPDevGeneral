package com.npdev.dsl.v1.pack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PK-3: a minimal version-constraint grammar for a {@code packs[].version} entry -- deliberately
 * narrow, matching {@code pack.schema.json}'s own {@code "^(\^)?\d+\.\d+(\.\d+)?$"} pattern.
 * Two kinds only, no ranges, no {@code ~}, no {@code ||}:
 *
 * <ul>
 *   <li>{@link Caret} ({@code "^2.0"}) -- compatible-within-major: same major, minor/patch at
 *       least as high.</li>
 *   <li>{@link Exact} ({@code "2.0.3"}, no leading {@code ^}) -- only that exact version.</li>
 * </ul>
 */
public sealed interface PackVersionConstraint {

    Pattern GRAMMAR = Pattern.compile("^(\\^)?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");

    static PackVersionConstraint parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        Matcher matcher = GRAMMAR.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Pack version constraint must be an exact version (\"2.0.3\") or a caret "
                            + "constraint (\"^2.0\"), got: " + raw);
        }
        boolean caret = matcher.group(1) != null;
        int major = Integer.parseInt(matcher.group(2));
        int minor = Integer.parseInt(matcher.group(3));
        int patch = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        PackVersion floor = new PackVersion(major, minor, patch);
        return caret ? new Caret(trimmed, floor) : new Exact(trimmed, floor);
    }

    /** True if the given pack version satisfies this constraint. */
    boolean satisfies(PackVersion actual);

    /** The major version this constraint requires -- used for MVS's cross-major pre-check. */
    int requiredMajor();

    /** The floor this constraint requires (for {@link Exact}, the only value that works). */
    PackVersion minimum();

    /** The original constraint string, for error messages. */
    String rawConstraint();

    record Caret(String rawConstraint, PackVersion minimum) implements PackVersionConstraint {
        @Override
        public boolean satisfies(PackVersion actual) {
            return actual.major() == minimum.major() && actual.compareTo(minimum) >= 0;
        }

        @Override
        public int requiredMajor() {
            return minimum.major();
        }
    }

    record Exact(String rawConstraint, PackVersion minimum) implements PackVersionConstraint {
        @Override
        public boolean satisfies(PackVersion actual) {
            return actual.equals(minimum);
        }

        @Override
        public int requiredMajor() {
            return minimum.major();
        }
    }
}
