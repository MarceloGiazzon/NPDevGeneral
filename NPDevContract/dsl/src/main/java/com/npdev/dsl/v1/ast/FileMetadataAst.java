package com.npdev.dsl.v1.ast;

import java.util.List;

/** LIFT-UPLOAD-P2: authored metadata for a `file`-typed field. */
public record FileMetadataAst(
        List<String> contentTypes,
        Long maxSizeBytes,
        boolean multiple
) {
    public FileMetadataAst {
        contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
    }
}
