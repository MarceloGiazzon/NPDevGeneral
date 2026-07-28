package com.npdev.generator.emitters;

import java.nio.file.Path;
import java.util.Map;

/**
 * Small text-generation helpers shared across more than one trusted-source template file
 * (quoting a Java string literal, reading a metadata value, deriving a bare file name).
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2).
 */
final class TrustedSourceTemplateSupport {

    private TrustedSourceTemplateSupport() {
    }

    static String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String fileName(String relativePath) {
        return Path.of(relativePath.replace('\\', '/')).getFileName().toString();
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
