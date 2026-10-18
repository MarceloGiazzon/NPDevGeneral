package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-1: the approvals pack's decision procedure, run the way a resumed flow runs it -- as the
 * person who started the flow, with the resume principal, through the governed concept gateway.
 */
class ApprovalsPackProcedureTest {

    @TempDir
    Path temp;

    private CompiledModel model;
    private ConceptGateway gateway;
    private InMemoryConceptStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path pack = Path.of("..", "..", "NPDevContract", "packs", "approvals", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.exists(pack), "approvals pack must exist at " + pack);
        Files.createDirectories(temp.resolve("packs/approvals"));
        Files.copy(pack, temp.resolve("packs/approvals/pack.json"));
        Files.writeString(temp.resolve("model.json"), """
                {
                  "namespace": "approvals.proc.test", "dslVersion": "1.0.0", "version": "1.0",
                  "packs": [ { "$ref": "packs/approvals/pack.json", "as": "approvals" } ],
                  "provides": { "roleBindings": { "approver": "MANAGER", "requester": "STAFF" } }
                }
                """);
        model = new ModelCompiler().compile(new JsonModelParser().parse(new ModelSourceResolver().resolve(temp.resolve("model.json"))));
        store = new InMemoryConceptStore(model);
        gateway = DefaultConceptGateway.governedBy(store, model);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", "r-1");
        request.put("title", "Buy a forklift");
        request.put("status", "PENDING");
        store.save(new ConceptRecord("approvals::ApprovalRequest", "r-1", "dev", request));
    }

    @Test
    void aDecisionMovesTheRequestAndLogsWhoDecidedWhenAndWhy() {
        ProcedureRunner runner = new ProcedureRunner(model, gateway, null, null);

        ProcedureExecutionResult result = runner.execute("approvals::ApplyApprovalDecision",
                Map.of("requestId", "r-1", "status", "APPROVED", "comment", "Go ahead", "completedBy", "bob"),
                ExecutionContext.resuming("dev", "alice"));

        assertTrue(result.ok(), result.failureCode() + ": " + result.failureMessage());
        ConceptRecord request = gateway.read(new ConceptReadRequest("approvals::ApprovalRequest", "r-1", null),
                ExecutionContext.resuming("dev", "alice")).orElseThrow();
        assertEquals("APPROVED", request.data().get("status"));
        assertEquals("bob", request.data().get("decidedBy"));
        assertEquals("Go ahead", request.data().get("decisionComment"));
        Map<String, Object> decision = store.findAll("dev", "approvals::ApprovalDecision").stream()
                .findFirst().orElseThrow(() -> new AssertionError("no decision row was logged")).data();
        assertEquals("r-1", decision.get("requestId"));
        assertEquals("bob", decision.get("completedBy"));
        assertEquals("APPROVED", decision.get("status"));
        assertTrue(decision.get("decidedAt") != null && !String.valueOf(decision.get("decidedAt")).isBlank(),
                "decidedAt defaults to now()");
    }

    @Test
    void aRequestThatAlreadyLeftPendingIsNotDecidedTwice() {
        ProcedureRunner runner = new ProcedureRunner(model, gateway, null, null);
        runner.execute("approvals::ApplyApprovalDecision",
                Map.of("requestId", "r-1", "status", "REJECTED", "comment", "No", "completedBy", "bob"),
                ExecutionContext.resuming("dev", "alice"));

        ProcedureExecutionResult second = runner.execute("approvals::ApplyApprovalDecision",
                Map.of("requestId", "r-1", "status", "APPROVED", "comment", "Changed mind", "completedBy", "carol"),
                ExecutionContext.resuming("dev", "alice"));

        assertTrue(second.ok(), second.failureCode() + ": " + second.failureMessage());
        ConceptRecord request = gateway.read(new ConceptReadRequest("approvals::ApprovalRequest", "r-1", null),
                ExecutionContext.resuming("dev", "alice")).orElseThrow();
        assertEquals("REJECTED", request.data().get("status"));
        assertEquals("bob", request.data().get("decidedBy"));
    }

    @Test
    void expiringAPendingRequestMarksItExpired() {
        ProcedureRunner runner = new ProcedureRunner(model, gateway, null, null);

        ProcedureExecutionResult result = runner.execute("approvals::ExpireApprovalRequest",
                Map.of("id", "r-1"), ExecutionContext.resuming("dev", "alice"));

        assertTrue(result.ok(), result.failureCode() + ": " + result.failureMessage());
        assertEquals("EXPIRED", gateway.read(new ConceptReadRequest("approvals::ApprovalRequest", "r-1", null),
                ExecutionContext.resuming("dev", "alice")).orElseThrow().data().get("status"));
    }
}
