package com.npdev.generator.migration;

import java.util.Locale;

public enum MigrationRiskThreshold {
    SAFE_ADDITIVE(0),
    BACKFILL_REQUIRED(1),
    MANUAL_REVIEW(2);

    private final int score;

    MigrationRiskThreshold(int score) {
        this.score = score;
    }

    public boolean allows(String overallRisk) {
        if (overallRisk == null || overallRisk.isBlank()) {
            return false;
        }
        return switch (overallRisk.trim().toUpperCase(Locale.ROOT)) {
            case "SAFE_ADDITIVE" -> score >= SAFE_ADDITIVE.score;
            case "BACKFILL_REQUIRED" -> score >= BACKFILL_REQUIRED.score;
            case "MANUAL_REVIEW" -> score >= MANUAL_REVIEW.score;
            case "BREAKING" -> false;
            default -> false;
        };
    }

    public static MigrationRiskThreshold parse(String value) {
        if (value == null || value.isBlank()) {
            return SAFE_ADDITIVE;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (MigrationRiskThreshold threshold : values()) {
            if (threshold.name().equals(normalized)) {
                return threshold;
            }
        }
        throw new IllegalArgumentException("Unsupported migration risk threshold: " + value);
    }
}
