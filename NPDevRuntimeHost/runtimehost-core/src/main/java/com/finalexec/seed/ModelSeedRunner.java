package com.finalexec.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSeed;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R8.8 (Roadmap Wave 2, 2026-08-19): the boot-time, automatic, idempotent executor for
 * model/pack-declared {@code seeds[]} ({@link CompiledModel#getSeeds()}) -- the compiled-model
 * counterpart to {@link SeedDataService}'s operator-triggered {@code definition/seeds/*.json}
 * convention. Follows {@code com.finalexec.workspace.WorkspaceMenuSeeder}'s own "NPDev's first
 * genuinely automatic... insert-if-empty" idiom rather than inventing a new idempotency mechanism:
 * for EACH distinct target concept among the declared seeds, if that concept already has any row
 * (for the configured tenant), every seed record targeting it is skipped entirely -- once ANY row
 * exists, an operator's own edits/deletes through generic CRUD are permanent, exactly like
 * WorkspaceMenuSeeder already guarantees for {@code workspace::Menu}. A no-op (self-disabling) for
 * any app whose compiled model declares no seeds at all -- unlike WorkspaceMenuSeeder, this is not
 * gated by {@code @ConditionalOnResource} (there is no classpath resource to gate on; the compiled
 * model is always present), so the emptiness check itself is what makes it a no-op.
 *
 * <p>Every record still saves through {@link ConceptGateway#save} -- {@link
 * SeedDataService#runRecords} is reused UNCHANGED for the actual expand ({@code $ref}/{@code $gen}/
 * {@code repeatOver}/{@code count})+resolve+save loop, so a model/pack-declared seed supports the
 * exact same record shape and templating the app-level convention already does. Ownership of a
 * pack-declared seed's target concept was already enforced at pack-composition time ({@code
 * ModelSourceResolver}), so every {@link CompiledSeed} reaching this class is trusted as-is.
 *
 * <p><b>Known limitation.</b> Skipping is granular per TARGET CONCEPT, not per record: if concept A
 * already has rows (skipped) but a LATER declared seed for concept B uses {@code "$ref:<alias>"}
 * naming an alias only A's (now-skipped) records would have registered, that reference fails to
 * resolve. This is the same "an alias must be declared earlier in the same run" ordering
 * constraint the app-level convention already has, just crossing a partial-skip boundary -- narrow
 * enough (small reference/lookup rows are the expected shape of a pack seed, not deep cross-concept
 * aliasing) that it is documented here rather than solved with per-record existence checks.
 */
@Component
public class ModelSeedRunner implements ApplicationRunner {

    private static final String RUN_ID = "model-seeds";
    private static final String KIND_SMART = "smart";

    private final CompiledModel compiledModel;
    private final ConceptGateway conceptGateway;
    private final SeedDataService seedDataService;
    private final ObjectMapper objectMapper;
    private final String tenantId;

    public ModelSeedRunner(
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            SeedDataService seedDataService,
            ObjectMapper objectMapper,
            @Value("${npdev.seed.model-seed.tenant-id:dev}") String tenantId
    ) {
        this.compiledModel = compiledModel;
        this.conceptGateway = conceptGateway;
        this.seedDataService = seedDataService;
        this.objectMapper = objectMapper;
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? "dev" : tenantId.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<CompiledSeed> seeds = compiledModel.getSeeds();
        if (seeds.isEmpty()) {
            return;
        }
        ExecutionContext context = ExecutionContext.system(tenantId);

        // insert-if-empty, evaluated once per distinct concept and cached -- a concept referenced
        // by 20 seed records only costs one list() round trip, not 20.
        Map<String, Boolean> emptyByConcept = new LinkedHashMap<>();
        List<JsonNode> toRun = new ArrayList<>();
        for (CompiledSeed seed : seeds) {
            String concept = seed.concept();
            if (concept == null || concept.isBlank()) {
                continue; // SeedValidation already refuses this at compile time; defensive only.
            }
            boolean empty = emptyByConcept.computeIfAbsent(concept, c -> isConceptEmpty(c, context));
            if (!empty) {
                continue;
            }
            toRun.add(toRecordNode(seed));
        }
        if (toRun.isEmpty()) {
            return;
        }

        SeedDataService.SeedRunResult result = seedDataService.runRecords(RUN_ID, KIND_SMART, toRun, context);
        if (result.ok()) {
            System.out.println("ModelSeedRunner: seeded " + result.createdCounts()
                    + " for tenant '" + tenantId + "'.");
        } else {
            System.out.println("ModelSeedRunner: seed run failed at record " + result.failedRecordIndex()
                    + " (concept " + result.failedConcept() + "): " + result.failureMessage()
                    + " -- " + result.createdCounts() + " row(s) saved before the failure.");
        }
    }

    private boolean isConceptEmpty(String concept, ExecutionContext context) {
        return conceptGateway
                .listCapped(new ConceptListRequest(concept, context.tenantId()), context, 1)
                .records()
                .isEmpty();
    }

    private JsonNode toRecordNode(CompiledSeed seed) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("concept", seed.concept());
        if (seed.alias() != null) {
            node.put("alias", seed.alias());
        }
        if (seed.id() != null) {
            node.put("id", seed.id());
        }
        node.set("data", objectMapper.valueToTree(seed.data()));
        if (!seed.repeatOverVars().isEmpty()) {
            node.set("repeatOver", toRepeatOverNode(seed.repeatOverVars()));
        }
        if (seed.count() != null) {
            node.put("count", seed.count());
        }
        return node;
    }

    private JsonNode toRepeatOverNode(Map<String, List<Integer>> repeatOverVars) {
        ObjectNode vars = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, List<Integer>> entry : repeatOverVars.entrySet()) {
            ArrayNode range = JsonNodeFactory.instance.arrayNode();
            entry.getValue().forEach(range::add);
            vars.set(entry.getKey(), range);
        }
        ObjectNode repeatOver = JsonNodeFactory.instance.objectNode();
        repeatOver.set("vars", vars);
        return repeatOver;
    }
}
