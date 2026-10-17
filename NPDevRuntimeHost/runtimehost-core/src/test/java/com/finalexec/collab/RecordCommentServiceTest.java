package com.finalexec.collab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.collab.RecordCommentService.CommentException;
import com.finalexec.collab.RecordCommentService.Failure;
import com.finalexec.collab.RecordCommentService.Item;
import com.finalexec.collab.RecordCommentService.Mention;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROC-5: the thread of a record is reachable exactly as far as the record itself is -- the real
 * {@link DefaultConceptGateway} with the concept's {@code access.read} row rule is the gate, so a draft
 * mosaic's thread is a 404 for everyone who cannot read the draft.
 */
class RecordCommentServiceTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.collab", "version": "1.0",
          "concepts": [
            { "name": "Mosaic", "ui": { "label": "Mosaic" },
              "collaboration": { "comments": true },
              "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "title", "type": "string" },
                { "name": "status", "type": "string" } ],
              "access": { "read": "status != 'DRAFT'" } },
            { "name": "Plain", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true } ] }
          ]
        }
        """;

    private InMemoryConceptStore conceptStore;
    private RecordCommentStore store;
    private RecordCommentService service;
    private final List<Mention> mentions = new ArrayList<>();
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
        service = new RecordCommentService(() -> model, () -> gateway, store, mentions::add, clock::incrementAndGet);
        save("Mosaic", "m-pub", Map.of("title", "Sunset", "status", "PUBLISHED"));
        save("Mosaic", "m-draft", Map.of("title", "Secret", "status", "DRAFT"));
        save("Plain", "p1", Map.of());
    }

    private void save(String concept, String id, Map<String, Object> data) {
        Map<String, Object> withId = new LinkedHashMap<>(data);
        withId.put("id", id);
        conceptStore.save(new ConceptRecord(concept, id, "default", withId));
    }

    private static Failure failureOf(Runnable action) {
        return assertThrows(CommentException.class, action::run).failure();
    }

    @Test
    void anUnreadableRecordIsNotFoundForListAndAddAndNothingIsStored() {
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.list("Mosaic", "m-draft", user("tito"))));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.add("Mosaic", "m-draft", "hello", user("tito"))));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.add("Mosaic", "no-such-row", "hello", user("tito"))));
        assertEquals(List.of(), store.list("default", "Mosaic", "m-draft", 10));
    }

    @Test
    void aConceptWithoutCollaborationIsNotFoundEvenForAReadableRecord() {
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.list("Plain", "p1", user("tito"))));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.add("Plain", "p1", "hi", user("tito"))));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.list("NoSuchConcept", "x", user("tito"))));
    }

    @Test
    void anotherTenantCannotSeeTheThread() {
        service.add("Mosaic", "m-pub", "visible to default only", user("tito"));
        ExecutionContext other = new ExecutionContext("other-tenant", "mallory", Map.of(), Set.of("USER"));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.list("Mosaic", "m-pub", other)));
    }

    @Test
    void addAndListRoundTripOldestFirstWithMine() {
        Item first = service.add("mosaic", "m-pub", "  first  ", user("tito"));
        service.add("Mosaic", "m-pub", "second", user("tavo"));
        assertEquals("first", first.body());
        assertTrue(first.mine());
        List<Item> items = service.list("Mosaic", "m-pub", user("tavo"));
        assertEquals(List.of("first", "second"), items.stream().map(Item::body).toList());
        assertEquals(List.of(false, true), items.stream().map(Item::mine).toList());
    }

    @Test
    void mentionsNotifyEachOtherUserOnceAndNeverTheAuthor() {
        service.add("Mosaic", "m-pub", "@tavo and @ana and @tavo please look, cc @tito", user("ana"));
        assertEquals(List.of("tavo", "tito"), mentions.stream().map(Mention::recipient).toList());
        Mention first = mentions.get(0);
        assertEquals("ana", first.author());
        assertEquals("Mosaic", first.conceptLabel());
        assertEquals("/npdev-business-ui/index.html#concept-Mosaic", first.link());
        assertEquals("comment:Mosaic/m-pub", first.source());
        assertTrue(first.excerpt().startsWith("@tavo and @ana"));
    }

    @Test
    void anEmailAddressIsNotAMentionAndAtMostTenAreNotified() {
        service.add("Mosaic", "m-pub", "write to bob@example.com", user("ana"));
        assertEquals(List.of(), mentions);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            many.append("@user").append(i).append(' ');
        }
        service.add("Mosaic", "m-pub", many.toString(), user("ana"));
        assertEquals(RecordCommentService.MAX_MENTIONS, mentions.size());
    }

    @Test
    void aFailingNotifierDoesNotFailTheComment() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        ConceptGateway gateway = DefaultConceptGateway.governedBy(conceptStore, model);
        RecordCommentService noisy = new RecordCommentService(() -> model, () -> gateway, store,
                m -> { throw new IllegalStateException("bell is down"); }, clock::incrementAndGet);
        noisy.add("Mosaic", "m-pub", "@tavo hi", user("ana"));
        assertEquals(1, store.list("default", "Mosaic", "m-pub", 10).size());
    }

    @Test
    void deleteIsForTheAuthorOrASuperuserOnly() {
        Item mine = service.add("Mosaic", "m-pub", "mine", user("tito"));
        assertEquals(Failure.FORBIDDEN, failureOf(() -> service.delete("Mosaic", "m-pub", mine.id(), user("tavo"))));
        assertEquals(1, service.list("Mosaic", "m-pub", user("tito")).size());

        ExecutionContext boss = new ExecutionContext("default", "boss", Map.of(), Set.of("SUPERUSER"));
        service.delete("Mosaic", "m-pub", mine.id(), boss);
        assertEquals(0, service.list("Mosaic", "m-pub", user("tito")).size());

        Item again = service.add("Mosaic", "m-pub", "again", user("tito"));
        service.delete("Mosaic", "m-pub", again.id(), user("tito"));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.delete("Mosaic", "m-pub", again.id(), user("tito"))),
                "a deleted comment is gone");
    }

    @Test
    void aCommentIdFromAnotherRecordCannotBeDeletedThroughThisOne() {
        save("Mosaic", "m-two", Map.of("title", "Two", "status", "PUBLISHED"));
        Item onTwo = service.add("Mosaic", "m-two", "on two", user("tito"));
        assertEquals(Failure.NOT_FOUND, failureOf(() -> service.delete("Mosaic", "m-pub", onTwo.id(), user("tito"))));
    }

    @Test
    void bodyMustBeOneToFourThousandCharacters() {
        assertEquals(Failure.BODY_INVALID, failureOf(() -> service.add("Mosaic", "m-pub", "x".repeat(4001), user("tito"))));
        assertEquals(Failure.BODY_INVALID, failureOf(() -> service.add("Mosaic", "m-pub", "   ", user("tito"))));
        assertEquals(Failure.BODY_INVALID, failureOf(() -> service.add("Mosaic", "m-pub", null, user("tito"))));
        assertEquals("x".repeat(4000), service.add("Mosaic", "m-pub", "x".repeat(4000), user("tito")).body());
    }
}
