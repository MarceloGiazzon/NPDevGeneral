package com.finalexec.collab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.collab.RecordCommentService.CommentException;
import com.finalexec.collab.RecordCommentService.Failure;
import com.finalexec.collab.RecordCommentService.Item;
import com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.FileHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROC-6: attachments share the thread's read gate -- a caller who cannot read the record can neither add nor
 * download, the key never reaches the client, and size / type limits apply only after the gate.
 */
class RecordAttachmentServiceTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.attach", "version": "1.0",
          "concepts": [
            { "name": "Mosaic", "collaboration": { "comments": true, "attachments": true },
              "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "status", "type": "string" } ],
              "access": { "read": "status != 'DRAFT'" } },
            { "name": "CommentsOnly", "collaboration": { "comments": true },
              "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
          ]
        }
        """;

    @TempDir
    Path tempDir;

    private InMemoryConceptStore conceptStore;
    private RecordCommentStore store;
    private FileSystemFileStoreAdapter files;
    private RecordCommentService service;
    private final AtomicLong clock = new AtomicLong(1_000);

    private static ExecutionContext user(String name) {
        return new ExecutionContext("default", name, Map.of(), Set.of("USER"));
    }

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        conceptStore = new InMemoryConceptStore(model);
        ConceptGateway gateway = DefaultConceptGateway.governedBy(conceptStore, model);
        store = new InMemoryRecordCommentStore();
        files = new FileSystemFileStoreAdapter(tempDir);
        service = new RecordCommentService(() -> model, () -> gateway, store, m -> { }, clock::incrementAndGet,
                () -> files, 1024, Set.of("application/pdf", "text/plain"));
        save("Mosaic", "m-pub", Map.of("status", "PUBLISHED"));
        save("Mosaic", "m-two", Map.of("status", "PUBLISHED"));
        save("Mosaic", "m-draft", Map.of("status", "DRAFT"));
        save("CommentsOnly", "c1", Map.of());
    }

    private void save(String concept, String id, Map<String, Object> data) {
        Map<String, Object> withId = new LinkedHashMap<>(data);
        withId.put("id", id);
        conceptStore.save(new ConceptRecord(concept, id, "default", withId));
    }

    private Item attach(String recordId, String name, String type, byte[] bytes, ExecutionContext ctx) {
        return service.addAttachment("Mosaic", recordId, name, type, bytes.length, new ByteArrayInputStream(bytes), ctx);
    }

    private static Failure failureOf(Runnable action) {
        return assertThrows(CommentException.class, action::run).failure();
    }

    @Test
    void aReaderAttachesAndAnotherReaderDownloadsTheSameBytes() {
        byte[] bytes = "hello pdf".getBytes(StandardCharsets.UTF_8);
        Item item = attach("m-pub", "..\\quote \"v2\".pdf", "application/pdf; charset=binary", bytes, user("tito"));
        assertEquals("ATTACHMENT", item.kind());
        assertEquals("quote v2.pdf", item.file().get("name"), "path parts and quotes are stripped from the name");
        assertEquals(bytes.length, ((Number) item.file().get("size")).intValue());
        assertEquals("application/pdf", item.file().get("contentType"));
        assertFalse(item.file().containsKey("key"), "the store key must never reach a client");

        assertEquals(1, service.list("Mosaic", "m-pub", user("tavo")).size());
        FileHandle handle = service.openAttachment("Mosaic", "m-pub", item.id(), user("tavo"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.copyAttachment(handle, out);
        assertArrayEquals(bytes, out.toByteArray());
    }

    @Test
    void anUnreadableRecordRefusesBothDirectionsAndStoresNothing() {
        assertEquals(Failure.NOT_FOUND, failureOf(() -> attach("m-draft", "a.pdf", "application/pdf", new byte[]{1}, user("tito"))));
        assertEquals(java.util.List.of(), store.list("default", "Mosaic", "m-draft", 10));
        assertTrue(files.listTenants().isEmpty() || files.list("default").isEmpty(), "no bytes were stored");

        Item ok = attach("m-pub", "a.pdf", "application/pdf", new byte[]{1, 2}, user("tito"));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.openAttachment("Mosaic", "m-draft", ok.id(), user("tavo"))),
                "an attachment id of a readable record cannot be fetched through an unreadable one");
    }

    @Test
    void anAttachmentIdOfAnotherRecordOrACommentIsNotFound() {
        Item onPub = attach("m-pub", "a.pdf", "application/pdf", new byte[]{1}, user("tito"));
        Item comment = service.add("Mosaic", "m-pub", "just text", user("tito"));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.openAttachment("Mosaic", "m-two", onPub.id(), user("tito"))));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.openAttachment("Mosaic", "m-pub", comment.id(), user("tito"))));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.openAttachment("Mosaic", "m-pub", "no-such-id", user("tito"))));
    }

    @Test
    void aConceptWithoutAttachmentsRefusesEvenThoughItHasComments() {
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.addAttachment("CommentsOnly", "c1", "a.pdf",
                "application/pdf", 1, new ByteArrayInputStream(new byte[]{1}), user("tito"))));
    }

    @Test
    void oversizeAndUnsupportedTypesAreRefusedAfterTheGate() {
        assertEquals(Failure.TOO_LARGE, failureOf(() -> attach("m-pub", "big.pdf", "application/pdf", new byte[1025], user("tito"))));
        assertEquals(Failure.UNSUPPORTED_TYPE, failureOf(() -> attach("m-pub", "x.html", "text/html", new byte[]{1}, user("tito"))));
        assertEquals(Failure.BODY_INVALID, failureOf(() -> attach("m-pub", "empty.pdf", "application/pdf", new byte[0], user("tito"))));
        // the gate comes first: an unreadable record never reveals the limits
        assertEquals(Failure.NOT_FOUND, failureOf(() -> attach("m-draft", "big.pdf", "application/pdf", new byte[1025], user("tito"))));
        assertEquals(java.util.List.of(), store.list("default", "Mosaic", "m-pub", 10));
    }

    @Test
    void aDeletedAttachmentCannotBeOpenedAndItsKeyIsNoLongerReferenced() {
        Item item = attach("m-pub", "a.pdf", "application/pdf", new byte[]{1}, user("tito"));
        assertEquals(1, store.attachmentKeys("default").size());
        assertTrue(store.attachmentKeys("other-tenant").isEmpty());
        service.delete("Mosaic", "m-pub", item.id(), user("tito"));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.openAttachment("Mosaic", "m-pub", item.id(), user("tito"))));
        assertTrue(store.attachmentKeys("default").isEmpty());
    }
}
