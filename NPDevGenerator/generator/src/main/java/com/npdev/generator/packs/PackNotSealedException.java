package com.npdev.generator.packs;

import java.util.List;

/**
 * BT-2 step 1: "Refuse to publish an unsealed pack as precompiled." Thrown by
 * {@link SealedPackBuilder#seal} naming every violation {@link com.npdev.dsl.v1.pack.PackSealednessAnalyzer}
 * found, so the refusal is actionable rather than a bare "no".
 */
public final class PackNotSealedException extends RuntimeException {

    public PackNotSealedException(String packId, List<String> violations) {
        super(buildMessage(packId, violations));
    }

    private static String buildMessage(String packId, List<String> violations) {
        StringBuilder message = new StringBuilder("Refusing to seal pack '").append(packId)
                .append("' -- it is not sealed (").append(violations.size())
                .append(violations.size() == 1 ? " violation):" : " violations):");
        for (String violation : violations) {
            message.append("\n  - ").append(violation);
        }
        return message.toString();
    }
}
