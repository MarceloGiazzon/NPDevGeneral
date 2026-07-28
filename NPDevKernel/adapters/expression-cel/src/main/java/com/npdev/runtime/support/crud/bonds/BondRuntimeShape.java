package com.npdev.runtime.support.crud.bonds;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the resolved shape of a multiple-
 * reference (many-to-many) bond field -- source/target concepts and fields plus the junction
 * table/column names it is persisted under.
 */
public record BondRuntimeShape(
        CompiledConcept sourceEntity,
        CompiledField sourceField,
        CompiledConcept targetEntity,
        CompiledField sourceIdField,
        CompiledField targetAnchorField,
        String junctionTable,
        String sourceColumn,
        String targetColumn
) {
}
