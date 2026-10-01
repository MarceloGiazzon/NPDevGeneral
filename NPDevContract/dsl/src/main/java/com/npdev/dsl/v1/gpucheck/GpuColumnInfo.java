package com.npdev.dsl.v1.gpucheck;

import java.util.List;

/** GPU-1 (G2): one packed column's shape, as {@link GpuCheckSupport#analyze} and
 *  {@link GpuWgslEmitter} need it. {@code scale} is fixed32-only (0 otherwise);
 *  {@code enumValues} is enum_index-only (declaration order defines the index). */
public record GpuColumnInfo(String encoding, int scale, List<String> enumValues) {
}
