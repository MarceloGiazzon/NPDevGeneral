package com.npdev.dsl.v1.pack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PK-3: a pack's own declared {@code version} ({@code "2.5.0"}), parsed once and compared many
 * times by {@link MinimalVersionSelector}. Distinct from {@link PackVersionConstraint}, which is
 * what a DEPENDENT declares it needs -- this is what a pack's own file actually IS.
 */
public record PackVersion(int major, int minor, int patch) implements Comparable<PackVersion> {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    public static PackVersion parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        Matcher matcher = PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Pack version must be exactly \"major.minor.patch\" (e.g. \"2.5.0\"), got: " + raw);
        }
        return new PackVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(PackVersion other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
