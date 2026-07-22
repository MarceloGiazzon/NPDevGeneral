package com.npdev.dsl.v1.compiled;

import java.util.List;

/** LIFT-UPLOAD-P2: compiled metadata for a `file`-typed field. */
public record CompiledFileMetadata(
        List<String> contentTypes,
        Long maxSizeBytes,
        boolean multiple
) {
    public CompiledFileMetadata {
        contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
    }
}
