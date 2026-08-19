package com.finalexec.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R3.2 -- generative seeds: {@code $gen:<generator>} value tokens and the {@code count} shorthand
 * in {@link SeedDataService#run}. Exercises the real expansion/resolution/save path against a
 * fake, in-memory {@link ConceptGateway} rather than a live app -- {@code SeedDataService} takes
 * plain constructor dependencies (no Spring context needed to exercise it), so this proves actual
 * runtime behaviour (record counts, field values, ordering, failure messages) without paying for a
 * booted FinalApp. Placed alongside {@code NpdevFileStoreConfigTest} in runtimehost-core's own
 * standalone test source set (rather than the top-level {@code NPDevRuntimeHost/src/test}
 * {@code SeedDataServiceListAvailableTest} lives in) so it can be run directly with
 * {@code ./gradlew test} in seconds instead of only inside the ~13-minute assembled-sample
 * RuntimeHost gate -- {@code SeedDataService} is itself app-independent (BT-1, no
 * {@code com.npdev.generated.} reference), which is exactly what this test source set is for.
 */
class SeedDataServiceGenerativeSeedTest {

    private static final ExecutionContext CONTEXT = ExecutionContext.of("default", "test-actor");

    // ---------------------------------------------------------------------------------------
    // Backward compatibility: a seed with no $gen tokens and no 'count' must behave exactly as
    // it did before this feature existed.
    // ---------------------------------------------------------------------------------------

    @Test
    void plainRecordsAndRepeatOverWithNoGenTokensStillWorkUnchanged() {
        String seedJson = """
                {
                  "id": "legacy", "label": "Legacy",
                  "records": [
                    {"concept": "Owner", "alias": "owner1", "data": {"name": "Fixed Owner"}},
                    {"concept": "Pet", "data": {"name": "Rex", "ownerId": "$ref:owner1"}},
                    {"concept": "Widget", "repeatOver": {"vars": {"n": [1, 3]}},
                     "data": {"label": "Widget $n"}}
                  ]
                }""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        SeedDataService service = serviceFor(seedJson, gateway);

        SeedDataService.SeedRunResult result = service.run("legacy", CONTEXT);

        assertTrue(result.ok(), () -> "expected success, got: " + result.failureMessage());
        assertEquals(1, result.createdCounts().get("Owner"));
        assertEquals(1, result.createdCounts().get("Pet"));
        assertEquals(3, result.createdCounts().get("Widget"));
        assertEquals("Rex", gateway.savedFor("Pet").get(0).data().get("name"));
        assertEquals(gateway.savedFor("Owner").get(0).id(), gateway.savedFor("Pet").get(0).data().get("ownerId"));
        List<Object> widgetLabels = gateway.savedFor("Widget").stream().map(r -> r.data().get("label")).toList();
        assertEquals(List.of("Widget 1", "Widget 2", "Widget 3"), widgetLabels);
    }

    // ---------------------------------------------------------------------------------------
    // 'count' shorthand
    // ---------------------------------------------------------------------------------------

    @Test
    void countShorthandBulkGeneratesTheDeclaredNumberOfRecords() {
        String seedJson = """
                {"id": "bulk", "label": "Bulk", "records": [
                  {"concept": "Item", "count": 7, "data": {"label": "static"}}
                ]}""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        SeedDataService service = serviceFor(seedJson, gateway);

        SeedDataService.SeedRunResult result = service.run("bulk", CONTEXT);

        assertTrue(result.ok(), () -> "expected success, got: " + result.failureMessage());
        assertEquals(7, result.createdCounts().get("Item"));
        assertEquals(7, gateway.savedFor("Item").size());
        // every id is unique -- count doesn't collapse rows onto one id
        assertEquals(7, gateway.savedFor("Item").stream().map(ConceptRecord::id).distinct().count());
    }

    // These two mirror the EXISTING 'alias'+'repeatOver' validation shape: expandSmartRecord's own
    // structural checks run before the per-record try/catch in run(), so a malformed declaration
    // (unlike a failed per-record save) throws straight out of run() rather than coming back as a
    // SeedRunResult.failure -- exactly what DataSeedAdminController's own
    // `catch (... | IllegalArgumentException)` -> HTTP 400 branch expects.

    @Test
    void countAndRepeatOverTogetherAreRejected() {
        String seedJson = """
                {"id": "bad", "label": "Bad", "records": [
                  {"concept": "Item", "count": 2, "repeatOver": {"vars": {"n": [1, 2]}}, "data": {"label": "x"}}
                ]}""";
        SeedDataService service = serviceFor(seedJson, new FakeConceptGateway());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.run("bad", CONTEXT));
        assertTrue(exception.getMessage().contains("both 'repeatOver' and 'count'"), exception.getMessage());
    }

    @Test
    void countWithAliasIsRejected() {
        String seedJson = """
                {"id": "bad2", "label": "Bad2", "records": [
                  {"concept": "Item", "alias": "x", "count": 2, "data": {"label": "x"}}
                ]}""";
        SeedDataService service = serviceFor(seedJson, new FakeConceptGateway());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.run("bad2", CONTEXT));
        assertTrue(exception.getMessage().contains("both 'alias' and 'count'"), exception.getMessage());
    }

    @Test
    void rawSeedRejectsCountAndGenTokens() {
        String countJson = """
                {"id": "raw1", "label": "Raw1", "kind": "raw", "records": [
                  {"concept": "Item", "count": 2, "data": {"label": "x"}}
                ]}""";
        SeedDataService serviceCount = serviceFor(countJson, new FakeConceptGateway());
        IllegalArgumentException countError = assertThrows(IllegalArgumentException.class,
                () -> serviceCount.run("raw1", CONTEXT));
        assertTrue(countError.getMessage().contains("'count'"), countError.getMessage());

        String genJson = """
                {"id": "raw2", "label": "Raw2", "kind": "raw", "records": [
                  {"concept": "Item", "data": {"label": "$gen:name"}}
                ]}""";
        SeedDataService serviceGen = serviceFor(genJson, new FakeConceptGateway());
        IllegalArgumentException genError = assertThrows(IllegalArgumentException.class,
                () -> serviceGen.run("raw2", CONTEXT));
        assertTrue(genError.getMessage().contains("$gen generator token"), genError.getMessage());
    }

    // ---------------------------------------------------------------------------------------
    // Individual $gen generators
    // ---------------------------------------------------------------------------------------

    @Test
    void genNameProducesATwoWordValueFromTheBuiltInCorpus() {
        String seedJson = """
                {"id": "names", "label": "Names", "records": [
                  {"concept": "Person", "count": 50, "data": {"name": "$gen:name"}}
                ]}""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        serviceFor(seedJson, gateway).run("names", CONTEXT);

        for (ConceptRecord record : gateway.savedFor("Person")) {
            String name = (String) record.data().get("name");
            assertEquals(2, name.split(" ").length, name);
        }
    }

    @Test
    void genWordsRespectsTheRequestedCount() {
        String seedJson = """
                {"id": "words", "label": "Words", "records": [
                  {"concept": "Note", "count": 20, "data": {"text": "$gen:words:4"}}
                ]}""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        serviceFor(seedJson, gateway).run("words", CONTEXT);

        for (ConceptRecord record : gateway.savedFor("Note")) {
            String text = (String) record.data().get("text");
            assertEquals(4, text.split(" ").length, text);
        }
    }

    @Test
    void genDateRangeStaysWithinTheDeclaredInclusiveBounds() {
        LocalDate min = LocalDate.parse("2020-01-01");
        LocalDate max = LocalDate.parse("2020-01-10");
        String seedJson = """
                {"id": "dates", "label": "Dates", "records": [
                  {"concept": "Event", "count": 100, "data": {"day": "$gen:date-range:2020-01-01:2020-01-10"}}
                ]}""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        serviceFor(seedJson, gateway).run("dates", CONTEXT);

        for (ConceptRecord record : gateway.savedFor("Event")) {
            LocalDate day = LocalDate.parse((String) record.data().get("day"));
            assertFalse(day.isBefore(min) || day.isAfter(max), day.toString());
        }
    }

    @Test
    void genDecimalRangeStaysWithinBounds() {
        BigDecimal min = new BigDecimal("10.00");
        BigDecimal max = new BigDecimal("20.00");
        String seedJson = """
                {"id": "decimals", "label": "Decimals", "records": [
                  {"concept": "Price", "count": 100, "data": {"amount": "$gen:decimal-range:10.00:20.00"}}
                ]}""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        serviceFor(seedJson, gateway).run("decimals", CONTEXT);

        for (ConceptRecord record : gateway.savedFor("Price")) {
            BigDecimal amount = new BigDecimal(record.data().get("amount").toString());
            assertFalse(amount.compareTo(min) < 0 || amount.compareTo(max) > 0, amount.toString());
        }
    }

    @Test
    void genEnumPickOnlyEverReturnsADeclaredValue() {
        Set<String> allowed = Set.of("PENDING", "SHIPPED", "DELIVERED");
        String seedJson = """
                {"id": "enums", "label": "Enums", "records": [
                  {"concept": "Order", "count": 50, "data": {"status": "$gen:enum-pick:PENDING,SHIPPED,DELIVERED"}}
                ]}""";
        FakeConceptGateway gateway = new FakeConceptGateway();
        serviceFor(seedJson, gateway).run("enums", CONTEXT);

        for (ConceptRecord record : gateway.savedFor("Order")) {
            assertTrue(allowed.contains(record.data().get("status")), String.valueOf(record.data().get("status")));
        }
    }

    // ---------------------------------------------------------------------------------------
    // ref-pick-random: ordering
    // ---------------------------------------------------------------------------------------

    private static final String CUSTOMER_THEN_ORDER_SEED = """
            {"id": "shop", "label": "Shop", "records": [
              {"concept": "Customer", "count": 5, "data": {
                  "name": "$gen:name", "notes": "$gen:words:3",
                  "birthDate": "$gen:date-range:1970-01-01:2005-12-31",
                  "balance": "$gen:decimal-range:10.00:500.00",
                  "tier": "$gen:enum-pick:BRONZE,SILVER,GOLD"
              }},
              {"concept": "Order", "count": 8, "data": {
                  "customerId": "$gen:ref-pick-random:Customer",
                  "amount": "$gen:decimal-range:5:250",
                  "status": "$gen:enum-pick:PENDING,SHIPPED,DELIVERED"
              }}
            ]}""";

    @Test
    void refPickRandomOnlyPicksAmongIdsAlreadyCreatedEarlierInTheFile() {
        FakeConceptGateway gateway = new FakeConceptGateway();
        SeedDataService.SeedRunResult result = serviceFor(CUSTOMER_THEN_ORDER_SEED, gateway).run("shop", CONTEXT);

        assertTrue(result.ok(), () -> "expected success, got: " + result.failureMessage());
        Set<String> customerIds = gateway.savedFor("Customer").stream().map(ConceptRecord::id)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(5, customerIds.size());
        for (ConceptRecord order : gateway.savedFor("Order")) {
            assertTrue(customerIds.contains(order.data().get("customerId")),
                    "Order.customerId " + order.data().get("customerId") + " is not one of the seeded Customer ids");
        }
    }

    @Test
    void refPickRandomFailsWithANamedErrorWhenTheReferentHasNotBeenSeededYet() {
        // Order declared BEFORE Customer -- this is the ordering hazard: ref-pick-random can only
        // see ids from concepts whose records were declared (and saved) earlier in the same file.
        String seedJson = """
                {"id": "backwards", "label": "Backwards", "records": [
                  {"concept": "Order", "count": 1, "data": {"customerId": "$gen:ref-pick-random:Customer"}},
                  {"concept": "Customer", "count": 1, "data": {"name": "$gen:name"}}
                ]}""";
        SeedDataService service = serviceFor(seedJson, new FakeConceptGateway());

        SeedDataService.SeedRunResult result = service.run("backwards", CONTEXT);

        assertFalse(result.ok());
        assertEquals("Order", result.failedConcept());
        assertTrue(result.failureMessage().contains("no records of concept 'Customer'"), result.failureMessage());
        assertTrue(result.failureMessage().contains("declare"), result.failureMessage());
    }

    // ---------------------------------------------------------------------------------------
    // Reproducibility -- the load-bearing claim: two runs of the SAME seed id produce IDENTICAL
    // generated data, proven by actually running it twice and diffing the output, not by
    // asserting the RNG was constructed with a seed.
    // ---------------------------------------------------------------------------------------

    @Test
    void twoRunsOfTheSameSeedIdProduceIdenticalGeneratedData() {
        FakeConceptGateway gatewayRun1 = new FakeConceptGateway();
        FakeConceptGateway gatewayRun2 = new FakeConceptGateway();
        SeedDataService.SeedRunResult result1 = serviceFor(CUSTOMER_THEN_ORDER_SEED, gatewayRun1).run("shop", CONTEXT);
        SeedDataService.SeedRunResult result2 = serviceFor(CUSTOMER_THEN_ORDER_SEED, gatewayRun2).run("shop", CONTEXT);

        assertTrue(result1.ok(), () -> "run 1 failed: " + result1.failureMessage());
        assertTrue(result2.ok(), () -> "run 2 failed: " + result2.failureMessage());

        List<ConceptRecord> customers1 = gatewayRun1.savedFor("Customer");
        List<ConceptRecord> customers2 = gatewayRun2.savedFor("Customer");
        assertEquals(customers1.size(), customers2.size());
        // Record ids are UUID.randomUUID() by design (see SeedDataService's class javadoc for why
        // ids are deliberately NOT seeded) -- so ids themselves differ between runs. Every OTHER
        // generated field must match exactly, in save order, between the two runs.
        for (int i = 0; i < customers1.size(); i++) {
            Map<String, Object> data1 = withoutId(customers1.get(i).data());
            Map<String, Object> data2 = withoutId(customers2.get(i).data());
            assertEquals(data1, data2, "Customer[" + i + "] differs between run 1 and run 2");
        }

        List<ConceptRecord> orders1 = gatewayRun1.savedFor("Order");
        List<ConceptRecord> orders2 = gatewayRun2.savedFor("Order");
        assertEquals(orders1.size(), orders2.size());
        for (int i = 0; i < orders1.size(); i++) {
            // amount/status compare directly...
            assertEquals(orders1.get(i).data().get("amount"), orders2.get(i).data().get("amount"),
                    "Order[" + i + "].amount differs between runs");
            assertEquals(orders1.get(i).data().get("status"), orders2.get(i).data().get("status"),
                    "Order[" + i + "].status differs between runs");
            // ...but customerId is itself a random-UUID Customer id, so compare by RELATIVE
            // POSITION in that run's own Customer pool instead of by literal value: ref-pick-random
            // must choose the same pool INDEX on both runs (same seeded Random, same draw order),
            // even though the id string at that index differs between runs.
            int index1 = idIndex(customers1, orders1.get(i).data().get("customerId"));
            int index2 = idIndex(customers2, orders2.get(i).data().get("customerId"));
            assertTrue(index1 >= 0 && index2 >= 0, "customerId did not resolve to a seeded Customer");
            assertEquals(index1, index2, "Order[" + i + "]'s referenced Customer POSITION differs between runs");
        }
    }

    @Test
    void differentSeedIdsProduceADifferentGeneratedSequence() {
        FakeConceptGateway gatewayA = new FakeConceptGateway();
        FakeConceptGateway gatewayB = new FakeConceptGateway();
        // Same file content served under two different seedId resource paths -- only the seedId
        // (the RNG seed) differs.
        SeedDataService serviceA = serviceForMultiple(Map.of(
                "classpath:npdev-seed/data-seeds/seed-a.json", CUSTOMER_THEN_ORDER_SEED), gatewayA);
        SeedDataService serviceB = serviceForMultiple(Map.of(
                "classpath:npdev-seed/data-seeds/seed-b.json", CUSTOMER_THEN_ORDER_SEED), gatewayB);
        serviceA.run("seed-a", CONTEXT);
        serviceB.run("seed-b", CONTEXT);

        List<Object> namesA = gatewayA.savedFor("Customer").stream().map(r -> r.data().get("name")).toList();
        List<Object> namesB = gatewayB.savedFor("Customer").stream().map(r -> r.data().get("name")).toList();
        assertNotEquals(namesA, namesB, "different seed ids should not draw the identical generator sequence");
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> withoutId(Map<String, Object> data) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(data);
        copy.remove("id");
        return copy;
    }

    private static int idIndex(List<ConceptRecord> records, Object id) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static SeedDataService serviceFor(String seedJson, ConceptGateway gateway) {
        String id = extractIdField(seedJson);
        return serviceForMultiple(Map.of("classpath:npdev-seed/data-seeds/" + id + ".json", seedJson), gateway);
    }

    private static String extractIdField(String seedJson) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([a-zA-Z0-9_-]+)\"").matcher(seedJson);
        if (!matcher.find()) {
            throw new IllegalStateException("test fixture seed JSON has no top-level id field");
        }
        return matcher.group(1);
    }

    private static SeedDataService serviceForMultiple(Map<String, String> resources, ConceptGateway gateway) {
        return new SeedDataService(new FakeResourceLoader(resources), gateway, new ObjectMapper());
    }

    private static final class FakeResourceLoader implements ResourceLoader {
        private final Map<String, String> resources;

        private FakeResourceLoader(Map<String, String> resources) {
            this.resources = resources;
        }

        @Override
        public Resource getResource(String location) {
            String content = resources.get(location);
            if (content == null) {
                return new ByteArrayResource(new byte[0]) {
                    @Override
                    public boolean exists() {
                        return false;
                    }
                };
            }
            return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public boolean exists() {
                    return true;
                }
            };
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }

    private static final class FakeConceptGateway implements ConceptGateway {
        private final List<ConceptRecord> saved = new ArrayList<>();

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return saved.stream()
                    .filter(r -> r.conceptName().equals(request.conceptName()) && r.id().equals(request.id()))
                    .findFirst();
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            return savedFor(request.conceptName());
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            ConceptRecord record = new ConceptRecord(request.conceptName(), request.id(), request.tenantId(), request.data());
            saved.add(record);
            return record;
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            saved.removeIf(r -> r.conceptName().equals(request.conceptName()) && r.id().equals(request.id()));
        }

        List<ConceptRecord> savedFor(String conceptName) {
            List<ConceptRecord> result = new ArrayList<>();
            for (ConceptRecord record : saved) {
                if (record.conceptName().equals(conceptName)) {
                    result.add(record);
                }
            }
            return result;
        }
    }
}
