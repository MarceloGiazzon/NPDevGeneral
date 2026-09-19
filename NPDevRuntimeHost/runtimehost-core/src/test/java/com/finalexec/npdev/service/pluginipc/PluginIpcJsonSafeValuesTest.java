package com.finalexec.npdev.service.pluginipc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
}
