package com.finalexec.pluginipc;

import com.finalexec.npdev.service.pluginipc.PluginIpcJsonSafeValues;
import com.finalexec.npdev.service.pluginipc.PluginIpcFrame;
import com.finalexec.npdev.service.pluginipc.PluginIpcFrameCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginIpcFrameCodecTest {

    @Test
    void roundTripsAnInvokeFrame() throws IOException {
        PluginIpcFrame.InvokeFrame frame = new PluginIpcFrame.InvokeFrame(
                "req-1", "auditLog", "AuditLogCapability", "auditlog-inproc", "append",
                List.of(Map.of("action", "LOGIN", "outcome", "SUCCESS")),
                "corr-1", null, Map.of("_npdevEntityName", "Session"),
                "com.example.AuditLogHandler"
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeInvoke(buffer, frame);

        PluginIpcFrame decoded = PluginIpcFrameCodec.readFrame(new ByteArrayInputStream(buffer.toByteArray()));

        assertInstanceOf(PluginIpcFrame.InvokeFrame.class, decoded);
        assertEquals(frame, decoded);
    }

    @Test
    void roundTripsACallbackFrame() throws IOException {
        PluginIpcFrame.CallbackFrame frame = new PluginIpcFrame.CallbackFrame(
                "req-1", "cb-1", "auditLog", "append", List.of(Map.of("action", "LOGIN"))
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeCallback(buffer, frame);

        PluginIpcFrame decoded = PluginIpcFrameCodec.readFrame(new ByteArrayInputStream(buffer.toByteArray()));

        assertInstanceOf(PluginIpcFrame.CallbackFrame.class, decoded);
        assertEquals(frame, decoded);
    }

    @Test
    void roundTripsASuccessAndAFailureResponseFrame() throws IOException {
        PluginIpcFrame.ResponseFrame success = PluginIpcFrame.ResponseFrame.success("req-1", Map.of("auditId", "a-1"));
        PluginIpcFrame.ResponseFrame failure = PluginIpcFrame.ResponseFrame.failure(
                "req-2", "PLUGIN_CALLBACK_NOT_DECLARED", "denied", "AUTH", Map.of("capability", "persistence")
        );
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeResponse(buffer, success);
        PluginIpcFrameCodec.writeResponse(buffer, failure);

        ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
        PluginIpcFrame decodedSuccess = PluginIpcFrameCodec.readFrame(in);
        PluginIpcFrame decodedFailure = PluginIpcFrameCodec.readFrame(in);

        assertEquals(success, decodedSuccess);
        assertEquals(failure, decodedFailure);
    }

    @Test
    void readsMultipleFramesWrittenBackToBackOnOneStream() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PluginIpcFrameCodec.writeInvoke(buffer, new PluginIpcFrame.InvokeFrame(
                "req-1", "auditLog", null, "auditlog-inproc", "append", List.of(), null, null, Map.of(), null
        ));
        PluginIpcFrameCodec.writeResponse(buffer, PluginIpcFrame.ResponseFrame.success("req-1", "ok"));

        ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
        assertInstanceOf(PluginIpcFrame.InvokeFrame.class, PluginIpcFrameCodec.readFrame(in));
        assertInstanceOf(PluginIpcFrame.ResponseFrame.class, PluginIpcFrameCodec.readFrame(in));
    }

    @Test
    void returnsNullAtCleanEndOfStream() throws IOException {
        assertNull(PluginIpcFrameCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void jsonSafeValuesAcceptsThePrimitivesMapsListsAndRecordsSubset() {
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(null));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe("text"));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(42));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(true));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(Map.of("k", List.of(1, "two", Map.of("nested", true)))));
        assertTrue(PluginIpcJsonSafeValues.isJsonSafe(new PluginIpcFrame.ResponseFrame("r", true, "v", null)));
    }

    @Test
    void jsonSafeValuesRejectsAnArbitraryObjectAndANonStringKeyedMap() {
        assertFalse(PluginIpcJsonSafeValues.isJsonSafe(new Object()));
        assertFalse(PluginIpcJsonSafeValues.isJsonSafe(Map.of(1, "value")));
    }

    @Test
    void jsonSafeValuesAcceptsAUuidBareAndNestedInsideMapsAndLists() {
        // REG-217: every uuid/reference-typed concept field is a native UUID in ConceptRecord.data()
        // (DslTypeCoercionSupport.normalizeFieldValue), so a listConcepts -> mapList -> callCapability
        // chain that copies an id field always builds args shaped like this.
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
