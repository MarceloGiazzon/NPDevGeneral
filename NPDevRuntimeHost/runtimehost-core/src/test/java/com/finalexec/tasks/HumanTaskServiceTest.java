package com.finalexec.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** PROC-2: the task inbox is a projection over parked flows; only assignees see and complete a task. */
class HumanTaskServiceTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.tasks", "version": "1.0",
          "concepts": [
            { "name": "Req", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "name", "type": "string" },
              { "name": "owner", "type": "string" } ] }
          ],
          "roles": [ { "name": "Manager" } ],
          "events": [ { "name": "Decided", "payload": [
            { "name": "refId", "type": "string" }, { "name": "outcome", "type": "string" } ] } ],
          "flows": [
            { "name": "ApproveReq", "concept": "Req", "steps": [
              { "name": "approve", "type": "awaitEvent", "awaitEvent": "Decided", "awaitRef": "decision",
                "match": { "correlation": true, "payload": { "refId": "$input.id" } }, "timeout": 3600,
                "task": {
                  "title": "Approve ${input.name}",
                  "assignee": { "user": "$input.owner", "roles": ["Manager"] },
                  "outcomes": [ { "value": "APPROVED", "label": "Approve" }, { "value": "REJECTED", "label": "Reject" } ],
                  "fields": [ { "name": "comment", "type": "string", "label": "Comment", "maxLength": 10 } ],
                  "record": "$input" } },
              { "name": "done", "type": "return", "value": "$input" } ] }
          ]
        }
        """;

    private record Published(String eventName, Map<String, Object> payload, String correlationId,
                             String causationId, ExecutionContext ctx) {
    }

    private final List<Published> published = new ArrayList<>();
    private InProcFlowInstanceStore store;
    private HumanTaskService service;
    private long now;

    private static ExecutionContext user(String tenant, String actor, String... roles) {
        return new ExecutionContext(tenant, actor, Map.of(), Set.of(roles));
    }

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        store = new InProcFlowInstanceStore();
        now = System.currentTimeMillis();
        service = new HumanTaskService(() -> model, store, (name, payload, corr, cause, ctx) -> {
            published.add(new Published(name, payload, corr, cause, ctx));
            // behave like the kernel: the matching waiter resumes and completes
            store.findByExecutionId(cause).ifPresent(i ->
                    store.update(i.markCompleted(i.state(), System.currentTimeMillis())));
            return EventEnvelope.create(name, payload, corr, cause, "ApproveReq", -1);
        });
        seedWaiting("exec-1", "t1", "c1", "r1", "Order 7", "ana", "approve");
    }

    private void seedWaiting(String executionId, String tenant, String correlation, String recordId,
                             String name, String owner, String stepName) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", recordId);
        input.put("name", name);
        input.put("owner", owner);
        Map<String, Object> await = new LinkedHashMap<>();
        await.put("stepName", stepName);
        await.put("payloadMatchRefs", Map.of("refId", "$input.id"));
        await.put("matchCorrelation", true);
        await.put("timeoutDeadlineEpochMs", now + 1000);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("input", input);
        state.put("correlationId", correlation);
        state.put("_npdev.await", await);
        FlowInstance parked = FlowInstance.start(executionId, "ApproveReq", correlation, tenant, "starter", state, now)
                .markWaiting(0, "Decided", state, now);
        store.save(parked);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboxShowsTasksOnlyToTheirAssigneesInTheirTenant() {
        List<Map<String, Object>> ana = service.myTasks(user("t1", "ana", "USER"));
        assertEquals(1, ana.size());
        assertEquals("Approve Order 7", ana.get(0).get("title"));
        assertEquals("exec-1", ana.get(0).get("executionId"));
        assertEquals(now + 1000, ((Number) ana.get(0).get("dueAtEpochMs")).longValue());
        assertEquals("r1", ana.get(0).get("recordId"));

        assertEquals(0, service.myTasks(user("t1", "bob", "USER")).size());
        assertEquals(1, service.myTasks(user("t1", "carl", "MANAGER")).size(), "role holders see it too");
        assertEquals(0, service.myTasks(user("other-tenant", "ana", "USER")).size());

        Map<String, Object> detail = service.detail("exec-1", user("t1", "ana", "USER"));
        assertEquals(2, ((List<Object>) detail.get("outcomes")).size());
        assertEquals("comment", ((Map<String, Object>) ((List<Object>) detail.get("fields")).get(0)).get("name"));
        assertEquals("outcome", detail.get("outcomeField"));
    }

    @Test
    void nonAssigneeCannotCompleteAndNothingIsPublished() {
        HumanTaskService.TaskException e = assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "approve", "APPROVED", Map.of(), user("t1", "bob", "USER")));
        assertEquals("TASK_FORBIDDEN", e.code());
        assertTrue(published.isEmpty());
        assertEquals("TASK_NOT_FOUND", assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "approve", "APPROVED", Map.of(), user("other-tenant", "ana", "USER"))).code());
    }

    @Test
    void assigneeCompletesAndTheAwaitedEventCarriesOutcomeFormValuesAndMatchPayload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("comment", "ok");
        values.put("junk", 1);
        Map<String, Object> result = service.complete("exec-1", "approve", "APPROVED", values, user("t1", "ana", "USER"));

        assertEquals(1, published.size());
        Published p = published.get(0);
        assertEquals("Decided", p.eventName());
        assertEquals("c1", p.correlationId());
        assertEquals("exec-1", p.causationId());
        assertEquals(Map.of("comment", "ok", "outcome", "APPROVED", "completedBy", "ana", "refId", "r1"), p.payload());
        assertFalse(p.payload().containsKey("junk"));
        assertEquals("COMPLETED", result.get("outcome"));
        assertEquals("COMPLETED", result.get("executionStatus"));

        // a second answer finds the task closed
        assertEquals("TASK_NOT_OPEN", assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "approve", "REJECTED", Map.of(), user("t1", "ana", "USER"))).code());
        assertEquals(0, service.myTasks(user("t1", "ana", "USER")).size());
    }

    @Test
    void invalidFormOutcomeAndStepNameAreRefused() {
        ExecutionContext ana = user("t1", "ana", "USER");
        assertEquals("TASK_FIELD_INVALID", assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "approve", "APPROVED", Map.of("comment", "this is far too long"), ana)).code());
        assertEquals("TASK_OUTCOME_INVALID", assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "approve", "MAYBE", Map.of(), ana)).code());
        assertEquals("TASK_NOT_OPEN", assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "some-other-step", "APPROVED", Map.of(), ana)).code());
        assertTrue(published.isEmpty());
    }

    @Test
    void completedInstanceIsNotOpen() {
        store.findByExecutionId("exec-1").ifPresent(i -> store.update(i.markCompleted(i.state(), now + 5)));
        assertEquals(FlowInstanceStatus.COMPLETED, store.findByExecutionId("exec-1").orElseThrow().status());
        assertEquals("TASK_NOT_OPEN", assertThrows(HumanTaskService.TaskException.class, () ->
                service.complete("exec-1", "approve", "APPROVED", Map.of(), user("t1", "ana", "USER"))).code());
        assertEquals(0, service.myTasks(user("t1", "ana", "USER")).size());
    }

    @Test
    void unresolvedAssigneeRefNeverMatchesAUserWithThatLiteralName() {
        seedWaiting("exec-2", "t1", "c2", "r2", "Order 8", null, "approve");
        // owner is unset -> the user ref resolves to null; the literal text of the ref must not grant access
        assertEquals(1, service.myTasks(user("t1", "ana", "USER")).size(), "only exec-1");
        assertEquals(0, service.myTasks(user("t1", "$input.owner", "USER")).size());
    }

    @Test
    void stateRefsReturnNullWhenUnresolved() {
        Map<String, Object> state = Map.of("saved", Map.of("code", "T-1"), "flat.key", 5);
        assertEquals("T-1", StateRefs.resolve(state, "$saved.code"));
        assertEquals(5, StateRefs.resolve(state, "flat.key"));
        assertNull(StateRefs.resolve(state, "$saved.missing"));
        assertNull(StateRefs.resolve(state, "$nothing.here"));
        assertEquals("T-1", StateRefs.flatten(state).get("saved.code"));
        Map<String, Object> wrapped = Map.of("saved", Map.of("id", "x", "data", Map.of("code", "T-2")));
        assertEquals("T-2", StateRefs.flatten(wrapped).get("saved.data.code"), "concept records nest their fields under data");
        assertEquals("T-2", StateRefs.resolve(wrapped, "$saved.data.code"));
    }
}
