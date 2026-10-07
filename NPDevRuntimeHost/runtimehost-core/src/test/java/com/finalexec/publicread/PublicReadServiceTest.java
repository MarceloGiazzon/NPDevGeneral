package com.finalexec.publicread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.filestore.TenantFileReader;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** P6 (G4): the anonymous read surface serves exactly what {@code access.public} grants, nothing else. */
class PublicReadServiceTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.publicread", "version": "1.0",
          "concepts": [
            { "name": "Mosaic", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "title", "type": "string" },
              { "name": "ownerEmail", "type": "string" },
              { "name": "status", "type": "string" },
              { "name": "photo", "type": "file", "file": { "contentTypes": ["image/png"] } } ],
              "access": { "public": { "where": "status == 'PUBLISHED' || status == 'BUILT'",
                                      "fields": ["title", "status", "photo"] } } },
            { "name": "MosaicCell", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "mosaicId", "type": "reference", "reference": { "target": "Mosaic" } },
              { "name": "row", "type": "integer" },
              { "name": "secretNote", "type": "string" } ],
              "access": { "public": { "fields": ["row"], "scope": "aggregate" } } },
            { "name": "Comment", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "mosaicId", "type": "reference", "reference": { "target": "Mosaic" } },
              { "name": "text", "type": "string" } ] }
          ],
          "aggregates": [
            { "name": "MosaicAggregate", "root": "Mosaic", "collections": [
              { "name": "cells", "concept": "MosaicCell", "childField": "mosaicId" },
              { "name": "comments", "concept": "Comment", "childField": "mosaicId" } ] }
          ]
        }
        """;

    private InMemoryConceptStore store;
    private PublicReadService service;
    private boolean tenantActive = true;

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        store = new InMemoryConceptStore(model);
        ConceptGateway gateway = DefaultConceptGateway.governedBy(store, model);
        service = new PublicReadService(() -> model, () -> gateway, new TenantFileReader(() -> null),
                "default", "/api/public", tenant -> tenantActive);
        save("Mosaic", "m-pub", Map.of("title", "Sunset", "ownerEmail", "tito@example.com", "status", "PUBLISHED",
                "photo", "{\"storeId\":\"local\",\"key\":\"default/p.png\"}"));
        save("Mosaic", "m-built", Map.of("title", "Flag", "ownerEmail", "tavo@example.com", "status", "BUILT"));
        save("Mosaic", "m-draft", Map.of("title", "Secret", "ownerEmail", "tito@example.com", "status", "DRAFT"));
        save("MosaicCell", "c1", Map.of("mosaicId", "m-pub", "row", 0, "secretNote", "x"));
        save("MosaicCell", "c2", Map.of("mosaicId", "m-draft", "row", 1, "secretNote", "y"));
        save("Comment", "k1", Map.of("mosaicId", "m-pub", "text", "nice"));
    }

    private void save(String concept, String id, Map<String, Object> data) {
        Map<String, Object> withId = new LinkedHashMap<>(data);
        withId.put("id", id);
        store.save(new ConceptRecord(concept, id, "default", withId));
    }

    @SuppressWarnings("unchecked")
    @Test
    void pageServesOnlyPublicRowsProjectedOntoTheAllowList() {
        Map<String, Object> page = service.page("mosaic", Map.of("sort", new String[]{"title"}));
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertEquals(2L, ((Number) page.get("total")).longValue(), "the DRAFT row must not even be counted");
        assertEquals(List.of("m-built", "m-pub"), items.stream().map(item -> item.get("id")).toList());
        Map<String, Object> sunset = items.get(1);
        assertEquals(List.of("id", "title", "status", "photo"), List.copyOf(sunset.keySet()));
        assertEquals(Map.of("url", "/api/public/concepts/Mosaic/m-pub/image/photo"), sunset.get("photo"),
                "a file field is a public image URL, never the raw store handle");
        assertNull(items.get(0).get("photo"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void callerFiltersAndSortsAreLimitedToPublicFieldsAndAndedWithTheGrant() {
        Map<String, String[]> params = new HashMap<>();
        params.put("status", new String[]{"DRAFT"});
        assertEquals(0L, ((Number) service.page("Mosaic", params).get("total")).longValue(),
                "a caller filter can only narrow the public rows, never reach a draft");
        assertThrows(IllegalArgumentException.class,
                () -> service.page("Mosaic", Map.of("ownerEmail", new String[]{"tito@example.com"})));
        assertThrows(IllegalArgumentException.class,
                () -> service.page("Mosaic", Map.of("sort", new String[]{"ownerEmail"})));
        Map<String, Object> limited = service.page("Mosaic", Map.of("limit", new String[]{"100000"}));
        assertEquals(PublicReadService.MAX_PAGE, limited.get("limit"));
    }

    @Test
    void privateRowsConceptsAndAggregateOnlyConceptsAreAllTheSameNotFound() {
        assertThrows(PublicReadService.NotPublicException.class, () -> service.get("Mosaic", "m-draft"));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.get("Mosaic", "nope"));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.page("Comment", Map.of()));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.page("MosaicCell", Map.of()),
                "scope aggregate has no direct route");
        assertThrows(PublicReadService.NotPublicException.class, () -> service.page("NoSuchConcept", Map.of()));
        assertEquals("Sunset", service.get("Mosaic", "m-pub").get("title"));
        assertFalse(service.get("Mosaic", "m-pub").containsKey("ownerEmail"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aggregateTreeServesPublicChildrenOnlyAndRefusesAPrivateRoot() {
        Map<String, Object> tree = service.aggregate("MosaicAggregate", "m-pub");
        List<Map<String, Object>> cells = (List<Map<String, Object>>) tree.get("cells");
        assertEquals(1, cells.size());
        assertEquals(Map.of("id", "c1", "row", 0), cells.get(0), "child projected onto its own allow-list");
        assertFalse(tree.containsKey("comments"), "a child concept without access.public is absent");
        assertFalse(tree.containsKey("ownerEmail"));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.aggregate("MosaicAggregate", "m-draft"));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.aggregate("Nope", "m-pub"));
    }

    @Test
    void imageIsServedOnlyForAnAllowListedFileFieldOfAPublicRow() {
        assertThrows(PublicReadService.NotPublicException.class, () -> service.image("Mosaic", "m-pub", "title"));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.image("Mosaic", "m-built", "photo"));
        assertThrows(PublicReadService.NotPublicException.class, () -> service.image("Mosaic", "m-draft", "photo"));
        // a public row whose handle cannot be resolved (no file store here) is a 404, never a 500 or a leak
        assertThrows(PublicReadService.NotPublicException.class, () -> service.image("Mosaic", "m-pub", "photo"));
    }

    @Test
    void aDisabledTenantServesNothing() {
        tenantActive = false;
        assertThrows(PublicReadService.NotPublicException.class, () -> service.page("Mosaic", Map.of()));
    }

    @Test
    void catalogListsOnlyDirectlyReadableConcepts() {
        assertEquals(List.of(Map.of("concept", "Mosaic", "fields", List.of("id", "title", "status", "photo"))),
                service.catalog());
    }

    @Test
    void combineDistributesExtraFiltersIntoEveryOrGroup() {
        ConceptQuery.Filter a = ConceptQuery.Filter.eq("status", "PUBLISHED");
        ConceptQuery.Filter b = ConceptQuery.Filter.eq("status", "BUILT");
        ConceptQuery.Filter extra = ConceptQuery.Filter.eq("id", "m1");
        List<ConceptQuery.Filter> combined = PublicReadService.combine(
                List.of(ConceptQuery.Filter.orGroups(List.of(List.of(a), List.of(b)))), List.of(extra));
        assertEquals(1, combined.size());
        assertEquals(List.of(List.of(a, extra), List.of(b, extra)), combined.get(0).value());
        assertEquals(List.of(a, extra), PublicReadService.combine(List.of(a), List.of(extra)));
    }

    @Test
    void rateLimiterRefusesPastTheLimitAndResetsAfterTheWindow() {
        AtomicLong now = new AtomicLong(1_000_000L);
        PublicReadRateLimiter limiter = new PublicReadRateLimiter(2, now::get);
        assertEquals(0, limiter.tryAcquire("1.2.3.4"));
        assertEquals(0, limiter.tryAcquire("1.2.3.4"));
        assertEquals(60, limiter.tryAcquire("1.2.3.4"));
        assertEquals(0, limiter.tryAcquire("5.6.7.8"), "limits are per client");
        now.addAndGet(60_000L);
        assertEquals(0, limiter.tryAcquire("1.2.3.4"));
        assertEquals(0, new PublicReadRateLimiter(0, now::get).tryAcquire("x"), "0 disables the limit");
    }
}
