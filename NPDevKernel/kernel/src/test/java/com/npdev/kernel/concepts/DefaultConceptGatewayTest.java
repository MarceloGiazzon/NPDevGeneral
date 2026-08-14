package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import com.npdev.kernel.security.PermissionDecision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConceptGatewayTest {

    @Test
    void saveAndReadUseContextTenantAndAppendAuditRecords() {
        CapturingAuditLogStore audit = new CapturingAuditLogStore();
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                (left, right) -> left.equals(right),
                audit
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("operator"));

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("UserAccount", "user-1", null, Map.of("email", "a@example.test")),
                context
        );
        Optional<ConceptRecord> loaded = gateway.read(
                new ConceptReadRequest("UserAccount", "user-1", "tenant-a"),
                context
        );

        assertEquals("tenant-a", saved.tenantId());
        assertTrue(loaded.isPresent());
        assertEquals("a@example.test", loaded.orElseThrow().data().get("email"));
        assertEquals(List.of("CONCEPT_WRITE", "CONCEPT_READ"), audit.records.stream().map(AuditRecord::action).toList());
        assertTrue(audit.records.stream().allMatch(record -> "tenant-a".equals(record.tenantId())));
    }

    /**
     * LNCH-16: two callers read the same row, both compute their edit against rowVersion 0. The
     * first writer wins and moves the row to rowVersion 1; the second (the loser of the race) must
     * be rejected with the winner's current state attached, not silently overwrite it.
     */
    @Test
    void interleavedUpdatesRejectLoserWithWinnersCurrentState() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");
        ConceptRecord created = gateway.save(
                new ConceptWriteRequest("UserAccount", "user-1", "tenant-a", Map.of("email", "a@example.test")),
                context
        );
        assertEquals(0L, created.rowVersion());

        ConceptRecord winnerWrite = gateway.save(
                new ConceptWriteRequest("UserAccount", "user-1", "tenant-a", Map.of("email", "winner@example.test"), 0L, false),
                context
        );
        assertEquals(1L, winnerWrite.rowVersion());
        assertEquals("winner@example.test", winnerWrite.data().get("email"));

        ConceptGatewayOptimisticLockException conflict = assertThrows(
                ConceptGatewayOptimisticLockException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("UserAccount", "user-1", "tenant-a", Map.of("email", "loser@example.test"), 0L, false),
                        context
                )
        );
        assertTrue(conflict.currentRecord().isPresent());
        assertEquals("winner@example.test", conflict.currentRecord().orElseThrow().data().get("email"));
        assertEquals(1L, conflict.currentRecord().orElseThrow().rowVersion());

        Optional<ConceptRecord> loaded = gateway.read(new ConceptReadRequest("UserAccount", "user-1", "tenant-a"), context);
        assertEquals("winner@example.test", loaded.orElseThrow().data().get("email"));
    }

    @Test
    void forceUpdateBypassesVersionCheckButStillIncrementsIt() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");
        gateway.save(new ConceptWriteRequest("UserAccount", "user-1", "tenant-a", Map.of("email", "a@example.test")), context);
        gateway.save(
                new ConceptWriteRequest("UserAccount", "user-1", "tenant-a", Map.of("email", "b@example.test"), 0L, false),
                context
        );

        ConceptRecord forced = gateway.save(
                new ConceptWriteRequest("UserAccount", "user-1", "tenant-a", Map.of("email", "forced@example.test"), 99L, true),
                context
        );

        assertEquals("forced@example.test", forced.data().get("email"));
        assertEquals(2L, forced.rowVersion());
    }

    @Test
    void rejectsCrossTenantReadsBeforeStoreLookup() {
        CapturingAuditLogStore audit = new CapturingAuditLogStore();
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                (left, right) -> left.equals(right),
                audit
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");

        ConceptGatewayAccessDeniedException exception = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.read(new ConceptReadRequest("UserAccount", "user-1", "tenant-b"), context)
        );

        assertEquals("TENANT_SCOPE_DENIED", exception.code());
        assertEquals(1, audit.records.size());
        assertEquals("DENIED", audit.records.get(0).outcome());
        assertEquals("tenant_scope_denied", audit.records.get(0).reasonCode());
    }

    /**
     * REG-52: {@code ExecutionContext}'s own constructor lowercases {@code tenantId} (REG-25), but
     * a per-request {@code tenantId} (here on {@code ConceptReadRequest}) only ever goes through
     * that record's own {@code normalizeOptional} -- trim only, never lowercased -- so it reaches
     * {@code TenantIsolationPolicy.STRICT_EQUALS} in whatever case the caller supplied. Using the
     * REAL {@code STRICT_EQUALS} (not the test-double {@code (left, right) -> left.equals(right)}
     * used elsewhere in this file, which is the same case-sensitive shape but not the production
     * policy) is deliberate: this proves the actual shipped policy, not a stand-in for it.
     */
    @Test
    void sameTenantMatchIsCaseInsensitiveEvenWhenARequestTenantIdBypassesExecutionContextNormalization() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop()
        );
        ExecutionContext context = ExecutionContext.of("Acme", "actor-a");
        gateway.save(new ConceptWriteRequest("UserAccount", "user-1", null, Map.of("email", "a@example.test")), context);

        Optional<ConceptRecord> loaded = gateway.read(
                new ConceptReadRequest("UserAccount", "user-1", "ACME"),
                context
        );

        assertTrue(loaded.isPresent(), "ACME and the context's normalized acme are the same logical "
                + "tenant -- a case difference alone must not deny the read");
    }

    @Test
    void rejectsWritesWithoutPermission() {
        CapturingAuditLogStore audit = new CapturingAuditLogStore();
        PermissionEvaluator denyWrites = (subject, requirement) -> {
            if ("concept.write".equals(requirement.permission())) {
                return PermissionDecision.deny("missing_permission", "missing concept write permission");
            }
            return PermissionDecision.allow("allowed");
        };
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                denyWrites,
                (left, right) -> left.equals(right),
                audit
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");

        ConceptGatewayAccessDeniedException exception = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(new ConceptWriteRequest("UserAccount", "user-1", null, Map.of()), context)
        );

        assertEquals("PERMISSION_DENIED", exception.code());
        assertEquals("DENIED", audit.records.get(0).outcome());
        assertEquals("missing_permission", audit.records.get(0).reasonCode());
    }

    @Test
    void saveRunsSemanticPolicyBeforePersistenceAndEmitsExplainableTrace() {
        CapturingAuditLogStore audit = new CapturingAuditLogStore();
        CapturingTraceSink trace = new CapturingTraceSink();
        SemanticPolicy semanticPolicy = new SemanticPolicy(false);
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                (left, right) -> left.equals(right),
                audit,
                semanticPolicy,
                trace
        );

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", 25)),
                ExecutionContext.of("tenant-a", "actor-a").withTag("executionMode", "headless")
        );

        assertEquals("draft", saved.data().get("status"));
        assertEquals(List.of(
                "normalizeAndValidate",
                "applyDefaultsAndDerivedValues",
                "validateLifecycleTransition",
                "evaluateRuleProfiles",
                "evaluateRuleProfiles"
        ), semanticPolicy.calls);

        List<ConceptGatewayTraceRecord> explain = gateway.explain();
        assertEquals(1, explain.size());
        ConceptGatewayTraceRecord record = explain.get(0);
        assertEquals(ConceptGatewayOperation.SAVE, record.operation());
        assertEquals("SUCCESS", record.outcome());
        assertTrue(record.ruleProfiles().contains("always"));
        assertTrue(record.ruleProfiles().contains("headless"));
        assertTrue(record.ruleProfiles().contains("beforeCommit"));
        assertTrue(record.ruleProfiles().contains("afterCommit"));
        assertEquals(List.of("status"), record.defaultsApplied());
        assertEquals("null->draft", record.lifecycleTransition());
        assertEquals("allowed", record.explanation());
    }

    @Test
    void semanticRuleProfileFailureIsAuditedTracedAndNotPersisted() {
        CapturingAuditLogStore audit = new CapturingAuditLogStore();
        CapturingTraceSink trace = new CapturingTraceSink();
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                (left, right) -> left.equals(right),
                audit,
                new SemanticPolicy(true),
                trace
        );

        ConceptGatewaySemanticException exception = assertThrows(
                ConceptGatewaySemanticException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", -1)),
                        ExecutionContext.of("tenant-a", "actor-a").withTag("executionMode", "interactive")
                )
        );

        assertEquals("AMOUNT_REJECTED", exception.code());
        assertTrue(gateway.read(
                new ConceptReadRequest("Expense", "expense-1", null),
                ExecutionContext.of("tenant-a", "actor-a")
        ).isEmpty());
        assertEquals("DENIED", audit.records.get(0).outcome());
        assertEquals("AMOUNT_REJECTED", audit.records.get(0).reasonCode());
        assertEquals("DENIED", trace.records.get(0).outcome());
        assertTrue(trace.records.get(0).ruleProfiles().contains("interactive"));
        assertEquals("amount must be positive", trace.records.get(0).explanation());
    }

    @Test
    void listUsesQueryRuleProfileAndFieldVisibility() {
        CapturingTraceSink trace = new CapturingTraceSink();
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                (left, right) -> left.equals(right),
                AuditLogStore.noop(),
                new SemanticPolicy(false),
                trace
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a")
                .withTag("executionMode", "interactive");

        gateway.save(new ConceptWriteRequest("Expense", "expense-1", null,
                Map.of("amount", 25, "secret", "hide-me")), context);

        List<ConceptRecord> records = gateway.list(new ConceptListRequest("Expense", null), context);

        assertEquals(1, records.size());
        assertFalse(records.get(0).data().containsKey("secret"));
        ConceptGatewayTraceRecord listTrace = trace.records.get(trace.records.size() - 1);
        assertEquals(ConceptGatewayOperation.LIST, listTrace.operation());
        assertTrue(listTrace.ruleProfiles().contains("query"));
        assertTrue(listTrace.ruleProfiles().contains("interactive"));
    }

    @Test
    void listWithFilterFieldReturnsOnlyMatchingRecords() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                (left, right) -> left.equals(right),
                AuditLogStore.noop(),
                new SemanticPolicy(false),
                new CapturingTraceSink()
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a")
                .withTag("executionMode", "interactive");

        gateway.save(new ConceptWriteRequest("MovimentoItem", "item-1", null,
                Map.of("amount", 10, "movimentoId", "mov-1", "quantidade", 10)), context);
        gateway.save(new ConceptWriteRequest("MovimentoItem", "item-2", null,
                Map.of("amount", 20, "movimentoId", "mov-2", "quantidade", 20)), context);
        gateway.save(new ConceptWriteRequest("MovimentoItem", "item-3", null,
                Map.of("amount", 30, "movimentoId", "mov-1", "quantidade", 30)), context);

        List<ConceptRecord> filtered = gateway.list(
                new ConceptListRequest("MovimentoItem", null, "movimentoId", "mov-1"), context);
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(record -> "mov-1".equals(record.data().get("movimentoId"))));

        List<ConceptRecord> unfiltered = gateway.list(new ConceptListRequest("MovimentoItem", null), context);
        assertEquals(3, unfiltered.size());

        List<ConceptRecord> noMatch = gateway.list(
                new ConceptListRequest("MovimentoItem", null, "movimentoId", "mov-does-not-exist"), context);
        assertTrue(noMatch.isEmpty());
    }

    /**
     * RUN-1 (R8a): {@link DefaultConceptGateway#listCapped} exactly AT the cap must not be reported
     * as truncated -- the off-by-one this method exists to get right. Exercises the SAME boundary
     * condition {@code JdbcBusinessConceptStore#findAllCapped}'s real SQL {@code LIMIT maxRows + 1}
     * pushdown relies on (see the sibling live-H2 test in runtimehost), generically over
     * {@code maxRows} rather than the literal 1000 the generated service uses -- the boundary logic
     * is the same regardless of the specific cap value.
     */
    @Test
    void listCappedExactlyAtTheCapIsNotReportedAsTruncated() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");

        for (int i = 0; i < 3; i++) {
            gateway.save(new ConceptWriteRequest("Widget", "widget-" + i, null, Map.of("name", "Widget " + i)), context);
        }

        ConceptListSlice<ConceptRecord> slice = gateway.listCapped(new ConceptListRequest("Widget", null), context, 3);

        assertEquals(3, slice.records().size());
        assertFalse(slice.truncated(), "exactly maxRows records must not be reported as truncated");
    }

    /** RUN-1 (R8a): one row OVER the cap must be truncated to exactly maxRows AND flagged. */
    @Test
    void listCappedOneRowOverTheCapIsTruncatedAndBoundedToMaxRows() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");

        for (int i = 0; i < 4; i++) {
            gateway.save(new ConceptWriteRequest("Widget", "widget-" + i, null, Map.of("name", "Widget " + i)), context);
        }

        ConceptListSlice<ConceptRecord> slice = gateway.listCapped(new ConceptListRequest("Widget", null), context, 3);

        assertEquals(3, slice.records().size(), "the response must never exceed maxRows");
        assertTrue(slice.truncated(), "maxRows + 1 records must be reported as truncated");
    }

    /** RUN-1 (R8a): under the cap, every record comes back and nothing is flagged. */
    @Test
    void listCappedUnderTheCapReturnsEverythingUntruncated() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");

        gateway.save(new ConceptWriteRequest("Widget", "widget-1", null, Map.of("name", "Widget 1")), context);

        ConceptListSlice<ConceptRecord> slice = gateway.listCapped(new ConceptListRequest("Widget", null), context, 3);

        assertEquals(1, slice.records().size());
        assertFalse(slice.truncated());
    }

    private static final class CapturingAuditLogStore implements AuditLogStore {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void append(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditRecord> search(AuditQuery query) {
            return List.copyOf(records);
        }
    }

    private static final class CapturingTraceSink implements ConceptGatewayTraceSink {
        private final List<ConceptGatewayTraceRecord> records = new ArrayList<>();

        @Override
        public void append(ConceptGatewayTraceRecord record) {
            records.add(record);
        }

        @Override
        public List<ConceptGatewayTraceRecord> records() {
            return List.copyOf(records);
        }
    }

    private static final class SemanticPolicy implements ConceptGatewaySemanticPolicy {
        private final List<String> calls = new ArrayList<>();
        private final boolean rejectProfile;

        private SemanticPolicy(boolean rejectProfile) {
            this.rejectProfile = rejectProfile;
        }

        @Override
        public ConceptSemanticDecision normalizeAndValidate(ConceptGatewayRequestContext request) {
            calls.add("normalizeAndValidate");
            if (request.data().containsKey("amount") && Number.class.isAssignableFrom(request.data().get("amount").getClass())) {
                return ConceptSemanticDecision.allow(request.data());
            }
            return ConceptSemanticDecision.deny("AMOUNT_REQUIRED", "amount is required");
        }

        @Override
        public ConceptSemanticDecision applyDefaultsAndDerivedValues(ConceptGatewayRequestContext request) {
            calls.add("applyDefaultsAndDerivedValues");
            Map<String, Object> nextData = new LinkedHashMap<>(request.data());
            if (!nextData.containsKey("status")) {
                nextData.put("status", "draft");
                return new ConceptSemanticDecision(
                        true,
                        "allowed",
                        "allowed",
                        nextData,
                        List.of(),
                        List.of("status"),
                        List.of(),
                        null,
                        Map.of()
                );
            }
            return ConceptSemanticDecision.allow(nextData);
        }

        @Override
        public ConceptSemanticDecision validateLifecycleTransition(ConceptGatewayRequestContext request) {
            calls.add("validateLifecycleTransition");
            String previous = request.previousRecord()
                    .map(record -> String.valueOf(record.data().get("status")))
                    .filter(value -> value != null && !value.isBlank() && !"null".equals(value))
                    .orElse("null");
            String next = String.valueOf(request.data().get("status"));
            return new ConceptSemanticDecision(
                    true,
                    "allowed",
                    "allowed",
                    request.data(),
                    List.of(),
                    List.of(),
                    List.of(),
                    previous + "->" + next,
                    Map.of()
            );
        }

        @Override
        public ConceptSemanticDecision evaluateRuleProfiles(
                ConceptGatewayRequestContext request,
                List<ConceptRuleProfile> ruleProfiles
        ) {
            calls.add("evaluateRuleProfiles");
            if (rejectProfile && ruleProfiles.contains(ConceptRuleProfile.BEFORE_COMMIT)) {
                return ConceptSemanticDecision.deny("AMOUNT_REJECTED", "amount must be positive")
                        .withRuleProfiles(ruleProfiles);
            }
            return ConceptSemanticDecision.allow(request.data()).withRuleProfiles(ruleProfiles);
        }

        @Override
        public ConceptRecord filterVisibleFields(ConceptRecord record, ConceptGatewayRequestContext request) {
            Map<String, Object> visible = new LinkedHashMap<>(record.data());
            visible.remove("secret");
            return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), visible);
        }
    }
}
