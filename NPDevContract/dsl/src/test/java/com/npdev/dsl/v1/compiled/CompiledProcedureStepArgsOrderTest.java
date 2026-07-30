package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A): {@code args} is the one Map field on this
 * record consumed POSITIONALLY -- {@code ProcedureRunner}/{@code NPDevCliMain}'s toProcedureStep
 * build a callCapability's argRefs from {@code args.values()} to reflectively invoke a multi-param
 * Java capability method by position ({@code ArtifactLocalJavaSourceCapabilityHandler}). Found the
 * hard way live on WmsOffice: a 3-arg callCapability (mapList-populated produtosConhecidos/
 * chavesJaImportadas into ParseNfeProcedure) worked on some app starts and failed with a reflection
 * "argument type mismatch" on others, from the exact same jar -- because the record's compact
 * constructor used {@code Map.copyOf}, whose iteration order is UNSPECIFIED and reshuffled by a
 * fresh per-JVM-run random salt for every {@code Map.of}/{@code Map.copyOf} instance, unlike
 * {@code List.copyOf} which always preserves insertion order. This test asserts iteration order is
 * preserved exactly as constructed, deterministically, regardless of which JVM process runs it.
 */
class CompiledProcedureStepArgsOrderTest {

    private static CompiledProcedureStep stepWithArgs(Map<String, Object> args) {
        return new CompiledProcedureStep(
                "call-fiscal-import", "capabilityCall", "parseResult", null, "", "", "", "", "",
                Map.of(), "", "", "", "fiscalImport", "importarNfe", "",
                args, List.of(), List.of(), List.of(), null, null, Map.of(), Map.of(), false, Map.of(), null, null
        );
    }

    @Test
    void argsIteratesInExactlyTheOrderConstructed() {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("chavesJaImportadas", "$chavesJaImportadas");
        declared.put("input", "$input");
        declared.put("produtosConhecidos", "$produtosConhecidos");

        CompiledProcedureStep step = stepWithArgs(declared);

        assertEquals(
                List.of("chavesJaImportadas", "input", "produtosConhecidos"),
                List.copyOf(step.args().keySet()),
                "args must iterate in the exact order it was constructed with -- a multi-arg "
                        + "callCapability's reflective dispatch is positional, so any reordering "
                        + "silently binds the wrong value to the wrong Java parameter"
        );
    }

    @Test
    void argsIterationOrderIsStableAcrossRepeatedConstructionFromTheSameSourceMap() {
        // Guards specifically against Map.copyOf's per-JVM-run random salt: constructing the SAME
        // step from the SAME source map repeatedly, within this one JVM, must always agree with
        // itself -- a necessary (not sufficient, since one run has only one salt) check that a
        // regression back to Map.copyOf would still often catch when the random iteration order
        // happens to disagree with insertion order at all.
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("zzzLast", "1");
        declared.put("aaaFirst", "2");
        declared.put("mmmMiddle", "3");

        List<String> expected = List.of("zzzLast", "aaaFirst", "mmmMiddle");
        for (int i = 0; i < 5; i++) {
            CompiledProcedureStep step = stepWithArgs(new LinkedHashMap<>(declared));
            assertEquals(expected, List.copyOf(step.args().keySet()),
                    "iteration order must match insertion order on attempt " + i);
        }
    }
}
