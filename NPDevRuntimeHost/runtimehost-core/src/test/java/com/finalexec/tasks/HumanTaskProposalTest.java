package com.finalexec.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.ai.ProposalHash;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI7-1: a task carrying a proposal shows a diff, hands the flow the PARKED values, and is audited. */
class HumanTaskProposalTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.review", "version": "1.0",
          "concepts": [
            { "name": "Entry", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "displayName", "type": "string" },
              { "name": "notes", "type": "string" } ] }
          ],
          "events": [ { "name": "Reviewed", "payload": [ { "name": "correlationId", "type": "string" } ] } ],
          "flows": [
            { "name": "ReviewEntry", "concept": "Entry", "steps": [
              { "name": "review", "type": "awaitEvent", "awaitEvent": "Reviewed", "awaitRef": "review",
                "match": { "correlation": true },
                "task": {
                  "title": "Review ${input.displayName}",
                  "assignee": { "roles": ["ADMIN"] },
                  "outcomeField": "decision",
                  "outcomes": [ { "value": "APPROVED" }, { "value": "REJECTED" } ],
                  "record": "$input",
                  "proposal": { "value": "$suggestion", "current": "$input" } } },
              { "name": "done", "type": "return", "value": "$review" } ] }
          ]
        }
        """;

    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<Map<String, Object>> published = new ArrayList<>();
    private InProcFlowInstanceStore store;
    private HumanTaskService service;
    private Map<String, Object> suggestion;

    private static ExecutionContext admin() {
        return new ExecutionContext("t1", "reviewer", Map.of(), Set.of("ADMIN"));
    }

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        store = new InProcFlowInstanceStore();
        AuditLogStore auditStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                audit.add(record);
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };
        service = new HumanTaskService(() -> model, store, (name, payload, corr, cause, ctx) -> {
            published.add(payload);
            return EventEnvelope.create(name, payload, corr, cause, "ReviewEntry", -1);
        }, auditStore);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "e1");
        input.put("displayName", "Old name");
        input.put("notes", "same notes");
        suggestion = new LinkedHashMap<>();
        suggestion.put("displayName", "New name");
        suggestion.put("notes", "same notes");
        Map<String, Object> await = new LinkedHashMap<>();
        await.put("stepName", "review");
        await.put("matchCorrelation", true);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("input", input);
        state.put("suggestion", suggestion);
        state.put("correlationId", "c1");
        state.put("_npdev.await", await);
        long now = System.currentTimeMillis();
        store.save(FlowInstance.start("exec-1", "ReviewEntry", "c1", "t1", "starter", state, now)
                .markWaiting(0, "Reviewed", state, now));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailShowsTheProposalAsADiffAgainstTheCurrentRecord() {
        Map<String, Object> detail = service.detail("exec-1", admin());
        Map<String, Object> proposal = (Map<String, Object>) detail.get("proposal");
        List<Map<String, Object>> diff = (List<Map<String, Object>>) proposal.get("diff");
        assertEquals(2, diff.size());
        Map<String, Object> name = diff.stream().filter(d -> "displayName".equals(d.get("field"))).findFirst().orElseThrow();
        assertEquals("Old name", name.get("before"));
        assertEquals("New name", name.get("after"));
        assertEquals(true, name.get("changed"));
        Map<String, Object> notes = diff.stream().filter(d -> "notes".equals(d.get("field"))).findFirst().orElseThrow();
        assertEquals(false, notes.get("changed"));
        assertEquals(ProposalHash.ofJson(suggestion), proposal.get("hash"));
        assertEquals(true, service.myTasks(admin()).get(0).get("review"));
    }

    @Test
    void approvalHandsTheFlowTheParkedValuesAndTheRecordIdNotAnythingTheClientSent() {
        Map<String, Object> clientValues = new LinkedHashMap<>();
        clientValues.put("proposal", Map.of("displayName", "TAMPERED"));
        clientValues.put("recordId", "other");
        service.complete("exec-1", "review", "APPROVED", clientValues, admin());

        assertEquals(1, published.size());
        Map<String, Object> payload = published.get(0);
        assertEquals(suggestion, payload.get("proposal"));
        assertEquals("e1", payload.get("recordId"));
        assertEquals("APPROVED", payload.get("decision"));
        assertEquals("reviewer", payload.get("completedBy"));
    }

    @Test
    void completionIsAuditedWithReviewerDecisionAndTheHashOfWhatTheReviewerSaw() {
        service.complete("exec-1", "review", "REJECTED", Map.of(), admin());

        assertEquals(1, audit.size());
        AuditRecord record = audit.get(0);
        assertEquals("humanTask.complete", record.action());
        assertEquals("exec-1", record.resourceId());
        assertEquals("reviewer", record.actorId());
        assertEquals("REJECTED", record.meta().get("decision"));
        assertEquals("reviewer", record.meta().get("reviewer"));
        assertEquals(ProposalHash.ofJson(suggestion), record.meta().get("proposalHash"));
        assertEquals("displayName,notes", record.meta().get("proposalFields"));
    }

    @Test
    void aRefusedCompletionLeavesNoAuditRow() {
        try {
            service.complete("exec-1", "review", "MAYBE", Map.of(), admin());
        } catch (HumanTaskService.TaskException expected) {
            // refused before anything was published
        }
        assertTrue(audit.isEmpty());
        assertTrue(published.isEmpty());
    }

    @Test
    void theHashIgnoresMapKeyOrder() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("notes", "same notes");
        reversed.put("displayName", "New name");
        assertEquals(ProposalHash.ofJson(suggestion), ProposalHash.ofJson(reversed));
        assertFalse(ProposalHash.ofJson(suggestion).equals(ProposalHash.ofJson(Map.of("displayName", "x"))));
        assertNull(null);
    }
}
