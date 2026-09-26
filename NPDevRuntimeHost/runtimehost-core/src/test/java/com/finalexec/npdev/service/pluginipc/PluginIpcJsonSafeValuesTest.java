package com.finalexec.npdev.service.pluginipc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-217 regression coverage: every uuid/reference-typed concept field is coerced to a native
 * {@link UUID} well before a procedure step ever sees it (DslTypeCoercionSupport.normalizeFieldValue),
 * so a {@code listConcepts -> mapList -> callCapability} chain that copies an id field always builds
 * args shaped like these tests, and used to fail {@link PluginIpcJsonSafeValues#isJsonSafe(Object)}.
 */
class PluginIpcJsonSafeValuesTest {

    @Test
    void isJsonSafeAcceptsAUuidBareAndNestedInsideMapsAndLists() {
        UUID id = UUID.randomUUID();
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(id));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(
                List.of(Map.of("loteId", id, "produtoId", UUID.randomUUID()))
        ));
    }

    @Test
    void roundTripsAnInvokeFrameCarryingAUuidValuedField() throws IOException {
        UUID loteId = UUID.randomUUID();
        PluginIpcFrame.InvokeFrame frame = new PluginIpcFrame.InvokeFrame(
                "req-1", "inventoryFile", "InventoryFileCapability", "inventoryfile-inproc", "gerarTemplate",
                List.of(List.of(Map.of("loteId", loteId, "quantidade", 3))),
                "corr-1", null, Map.of(), null
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeInvoke(buffer, frame);

        PluginIpcFrame decoded = PluginIpcFrameCodec.readFrame(new ByteArrayInputStream(buffer.toByteArray()));

        assertInstanceOf(PluginIpcFrame.InvokeFrame.class, decoded);
        PluginIpcFrame.InvokeFrame invoke = (PluginIpcFrame.InvokeFrame) decoded;
        List<?> ocupacoes = (List<?>) invoke.args().get(0);
        Map<?, ?> ocupacao = (Map<?, ?>) ocupacoes.get(0);
        // Same string a plugin's String.valueOf(...)/nullToEmpty(...) would already have produced from
        // the raw UUID in the in-process path -- the wire round-trip changes representation, not value.
        assertEquals(loteId.toString(), ocupacao.get("loteId"));
    }

    /**
     * WMS-14 regression coverage: a "date"/"datetime" concept field (e.g. WmsOffice's
     * {@code Lote.dataValidade}) is coerced to a native {@link LocalDate}/{@link OffsetDateTime} the
     * same way a uuid/reference field is coerced to {@link UUID}
     * (DslTypeCoercionSupport.normalizeByDslType), so a {@code listConcepts -> mapList ->
     * callCapability} chain copying a date field hit the identical
     * {@code PLUGIN_EXECUTION_FAILED "... is not JSON-safe: java.util.ArrayList"} symptom REG-217
     * described, just for a different nested type.
     */
    @Test
    void isJsonSafeAcceptsALocalDateAndOffsetDateTimeBareAndNestedInsideMapsAndLists() {
        LocalDate dataValidade = LocalDate.of(2027, 1, 1);
        OffsetDateTime dataGeracao = OffsetDateTime.of(2026, 9, 26, 12, 0, 0, 0, ZoneOffset.UTC);
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(dataValidade));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(dataGeracao));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(
                List.of(Map.of("dataValidade", dataValidade, "dataGeracao", dataGeracao))
        ));
    }

    /**
     * {@code PluginIpcFrameCodec} itself deliberately has no {@code JavaTimeModule} registered (a real
     * plugin child process's restricted classpath does not reliably have {@code jackson-datatype-jsr310}
     * resolvable -- see {@link PluginIpcJsonSafeValues}'s own class doc), so a caller must
     * {@link PluginIpcJsonSafeValues#sanitizeForWire} the args BEFORE building the frame, exactly as
     * {@code PluginIpcHostSession}/{@code PluginIpcCallbackClient}/{@code PluginIpcChildRuntime} now do.
     */
    @Test
    void roundTripsAnInvokeFrameCarryingASanitizedLocalDateValuedField() throws IOException {
        LocalDate dataValidade = LocalDate.of(2027, 1, 1);
        List<Object> rawArgs = List.<Object>of(List.of(Map.of("id", UUID.randomUUID(), "dataValidade", dataValidade)));
        List<Object> safeArgs = PluginIpcJsonSafeValues.sanitizeArgsForWire(rawArgs);
        PluginIpcFrame.InvokeFrame frame = new PluginIpcFrame.InvokeFrame(
                "req-2", "alocacao", "AllocationCapability", "alocacao-inproc", "enderecarRecebimento",
                safeArgs, "corr-2", null, Map.of(), null
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeInvoke(buffer, frame);

        PluginIpcFrame decoded = PluginIpcFrameCodec.readFrame(new ByteArrayInputStream(buffer.toByteArray()));

        assertInstanceOf(PluginIpcFrame.InvokeFrame.class, decoded);
        PluginIpcFrame.InvokeFrame invoke = (PluginIpcFrame.InvokeFrame) decoded;
        List<?> lotes = (List<?>) invoke.args().get(0);
        Map<?, ?> lote = (Map<?, ?>) lotes.get(0);
        // ISO-8601, the same string every other JSON surface in this platform already produces for a
        // date field -- sanitizing to a String before the codec ever sees it changes representation,
        // not value.
        assertEquals(dataValidade.toString(), lote.get("dataValidade"));
    }

    /**
     * WMS-14, the confirmed real leaf type (not the {@link LocalDate} guess above, which turned out to
     * be incomplete): WmsOffice's H2Local storage layer returns a raw {@code java.sql.Date} for a
     * "date"-typed concept field -- {@code DslTypeCoercionSupport.normalizeFieldValue} has no "date"
     * case at all, so whatever the storage layer's read path produces flows straight through unchanged.
     * This exact live failure ({@code invoke.args[1][0].dataValidade is not JSON-safe:
     * java.sql.Date}), surfaced by this same class's improved leaf-path diagnostics, is what identified
     * the real type.
     */
    @Test
    void isJsonSafeAcceptsASqlDateBareAndNestedInsideMapsAndLists() {
        java.sql.Date dataValidade = java.sql.Date.valueOf(LocalDate.of(2027, 1, 1));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(dataValidade));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(
                List.of(Map.of("dataValidade", dataValidade))
        ));
    }

    @Test
    void roundTripsAnInvokeFrameCarryingASanitizedSqlDateValuedField() throws IOException {
        java.sql.Date dataValidade = java.sql.Date.valueOf(LocalDate.of(2027, 1, 1));
        List<Object> rawArgs = List.<Object>of(List.of(Map.of("id", UUID.randomUUID(), "dataValidade", dataValidade)));
        List<Object> safeArgs = PluginIpcJsonSafeValues.sanitizeArgsForWire(rawArgs);
        PluginIpcFrame.InvokeFrame frame = new PluginIpcFrame.InvokeFrame(
                "req-3", "alocacao", "AllocationCapability", "alocacao-inproc", "enderecarRecebimento",
                safeArgs, "corr-3", null, Map.of(), null
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeInvoke(buffer, frame);

        PluginIpcFrame decoded = PluginIpcFrameCodec.readFrame(new ByteArrayInputStream(buffer.toByteArray()));

        assertInstanceOf(PluginIpcFrame.InvokeFrame.class, decoded);
        PluginIpcFrame.InvokeFrame invoke = (PluginIpcFrame.InvokeFrame) decoded;
        List<?> lotes = (List<?>) invoke.args().get(0);
        Map<?, ?> lote = (Map<?, ?>) lotes.get(0);
        // java.sql.Date.toString() is always "yyyy-MM-dd" -- sanitizing to a String before the codec
        // ever sees it changes representation, not value.
        assertEquals("2027-01-01", lote.get("dataValidade"));
    }

    @Test
    void sanitizeForWireLeavesUuidStringsBooleansAndNumbersUntouched() {
        UUID id = UUID.randomUUID();
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("id", id);
        input.put("name", "Produto Demo");
        input.put("ativo", true);
        input.put("quantidade", 37L);
        Object sanitized = PluginIpcJsonSafeValues.sanitizeForWire(List.of(input));
        List<?> list = (List<?>) sanitized;
        Map<?, ?> map = (Map<?, ?>) list.get(0);
        assertEquals(id, map.get("id"));
        assertEquals("Produto Demo", map.get("name"));
        assertEquals(true, map.get("ativo"));
        assertEquals(37L, map.get("quantidade"));
    }

    @Test
    void requireJsonSafeReportsTheExactNestedLeafPathAndTypeNotJustTheOuterContainer() {
        // A genuinely unsafe leaf type -- java.sql.Date/LocalDate/OffsetDateTime/UUID are all accepted
        // now, so a bare Object stands in for "some future type nobody has added a case for yet".
        Object badLeaf = new Object();
        // "args[1]" itself is the lotes list (List<Map>), matching requireJsonSafeArgs's own per-arg
        // loop -- one level, not wrapped in another list, mirroring the real invoke.args[1] shape.
        Object badArgs = List.of(Map.of("id", UUID.randomUUID(), "dataValidade", badLeaf));
        PluginIpcJsonSafeValues.NotJsonSafeException exception = org.junit.jupiter.api.Assertions.assertThrows(
                PluginIpcJsonSafeValues.NotJsonSafeException.class,
                () -> PluginIpcJsonSafeValues.requireJsonSafe("invoke.args[1]", badArgs)
        );
        // Before this fix, the message named only the outer "java.util.ArrayList" (REG-217's own
        // documented diagnostics gap). It now names the exact path and leaf type.
        assertTrue(exception.getMessage().contains("invoke.args[1][0].dataValidade"));
        assertTrue(exception.getMessage().contains("java.lang.Object"));
    }
}
