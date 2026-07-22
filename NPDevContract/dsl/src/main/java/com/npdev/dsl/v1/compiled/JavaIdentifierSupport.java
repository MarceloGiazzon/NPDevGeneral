package com.npdev.dsl.v1.compiled;

/**
 * Shared Java identifier normalization for compiled model names.
 */
public final class JavaIdentifierSupport {
    private JavaIdentifierSupport() {
    }

    public static String className(String authoredName) {
        if (authoredName == null || authoredName.isBlank()) {
            return authoredName;
        }
        String[] namespaceParts = authoredName.trim().split("::");
        StringBuilder out = new StringBuilder();
        for (String namespacePart : namespaceParts) {
            appendPascalTokens(out, namespacePart);
        }
        if (out.isEmpty()) {
            return "_";
        }
        if (!Character.isJavaIdentifierStart(out.charAt(0))) {
            out.insert(0, '_');
        }
        return out.toString();
    }

    private static void appendPascalTokens(StringBuilder out, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        boolean capitalizeNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isJavaIdentifierPart(current) || current == '_') {
                capitalizeNext = true;
                continue;
            }
            if (capitalizeNext) {
                out.append(Character.toUpperCase(current));
                capitalizeNext = false;
            } else {
                out.append(current);
            }
        }
    }
}
