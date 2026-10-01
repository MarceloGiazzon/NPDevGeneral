package com.npdev.dsl.v1.gpucheck;

import java.util.List;

/** GPU-1 (G2.1): one column of a check pack, exactly the shape {@code gpu-check-manifest.schema.json}'s
 *  {@code packs[].columns[]} declares. {@code word} = {@code nullBit + 1} (word 0 of every row is the
 *  null bitmask); {@code scale} is fixed32-only, {@code enumValues} is enum_index-only. */
public record GpuManifestColumn(int word, int nullBit, String field, String column, String encoding,
        Integer scale, List<String> enumValues) {
}
