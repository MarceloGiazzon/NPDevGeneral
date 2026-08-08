package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledGroupByField;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.storage.sql.StorageCapability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * S3: <b>honesty precedes variety.</b> Refuses, at GENERATION time, a model that needs something the
 * chosen storage engine cannot do.
 *
 * <p><b>Why generation time and not boot time.</b> Refusing late is the same defect as not refusing.
 * A model that needs server-side joins on an engine that has none does not fail at boot -- it fails
 * on the first query that happens to run that path, in production, as wrong or missing data. The
 * engine is fixed when the app is generated, and every requirement below is readable from the
 * compiled model, so there is no reason to wait.
 *
 * <p><b>Why it must exist BEFORE a second engine.</b> With one SQL engine every capability is
 * present and this gate never fires. That is precisely when to build it: adding MySQL, and later a
 * document store, means capabilities start going missing, and a platform that discovers that by
 * emitting an app that silently does less is the worst outcome this work can produce.
 *
 * <p><b>The message names the model element.</b> "SERVER_SIDE_JOIN is unsupported" tells an author
 * nothing they can act on; "the join in query 'orderSummary' (Order.customer.region)" tells them
 * exactly what to change. Each requirement therefore carries the element that created it, and the
 * refusal lists both ways out: pick an engine that can, or change that element.
 */
public final class StorageCapabilityGate {

    /** One thing in the model that needs one capability, with the element that asked for it. */
    public record Requirement(StorageCapability capability, String element, String because) {
    }

    private StorageCapabilityGate() {
    }

    /**
     * Refuse if {@code plan}'s engine cannot do what {@code model} needs.
     *
     * @throws UnsupportedStorageEngineException listing every unmet capability at once -- not the
     *         first one. An author who has to regenerate five times to discover five problems will
     *         reasonably conclude the tool is guessing.
     */
    public static void verify(CompiledModel model, GeneratedDatabasePlan plan) {
        if (plan == null || !plan.jdbc()) {
            // InMemory stores nothing in SQL and has no dialect to ask. Not a silent pass: jdbc() is
            // the explicit question, and DatabaseEngine.dialect() throws for IN_MEMORY rather than
            // handing back a Postgres answer that would be wrong in every particular.
            return;
        }
        verifyAgainst(model, plan.engine().dialect(), plan.engine().externalName());
    }

    /** Testable core: the same check against an explicit dialect. */
    public static void verifyAgainst(CompiledModel model, SqlDialect dialect, String engineName) {
        Set<StorageCapability> supported = dialect.capabilities();
        Map<StorageCapability, List<Requirement>> unmet = new LinkedHashMap<>();
        for (Requirement requirement : requirementsOf(model)) {
            if (!supported.contains(requirement.capability())) {
                unmet.computeIfAbsent(requirement.capability(), key -> new ArrayList<>()).add(requirement);
            }
        }
        if (!unmet.isEmpty()) {
            throw new UnsupportedStorageEngineException(engineName, dialect.name(), unmet, alternativesFor(unmet.keySet()));
        }
    }

    /**
     * Every capability this model needs, and the element that needs it.
     *
     * <p>Public because {@code npdev doctor} renders it: "what would this model need" is the same
     * question, asked without an engine in hand.
     */
    public static List<Requirement> requirementsOf(CompiledModel model) {
        List<Requirement> out = new ArrayList<>();
        if (model == null) {
            return out;
        }

        // Any persisted app needs these two. Stated rather than assumed, so a document engine that
        // lacks multi-document transactions is refused by the same mechanism as everything else
        // rather than by a special case somebody has to remember to write.
        out.add(new Requirement(StorageCapability.TRANSACTIONS, "the application itself",
                "a write that fails partway must not leave half a record behind"));
        out.add(new Requirement(StorageCapability.SCHEMA_EVOLUTION, "the application itself",
                "NPDev regenerates and re-realizes the schema on every model change"));

        for (CompiledConcept concept : model.getConcepts()) {
            String conceptName = concept.getName();

            for (CompiledField field : concept.getFields()) {
                if (field.isUnique()) {
                    out.add(new Requirement(StorageCapability.UNIQUE_CONSTRAINTS,
                            conceptName + "." + field.getName(),
                            "the field is declared unique, and uniqueness enforced in application code "
                            + "loses to a concurrent insert"));
                }
                if (field.getReferenceTarget() != null && !field.getReferenceTarget().isBlank()) {
                    out.add(new Requirement(StorageCapability.FOREIGN_KEYS,
                            conceptName + "." + field.getName(),
                            "the field references " + field.getReferenceTarget()
                            + ", and referential integrity enforced only in application code drifts"));
                }
            }

            for (CompiledIndex index : concept.getIndexes()) {
                if (index.isUnique()) {
                    out.add(new Requirement(StorageCapability.UNIQUE_CONSTRAINTS,
                            conceptName + " index '" + index.getName() + "'",
                            "the index is declared unique"));
                }
            }

            for (CompiledInvariant invariant : concept.getInvariants()) {
                if ("unique".equalsIgnoreCase(invariant.getType())) {
                    out.add(new Requirement(StorageCapability.UNIQUE_CONSTRAINTS,
                            conceptName + " invariant '" + invariant.getRef() + "'",
                            "the invariant declares uniqueness"));
                }
            }
        }

        for (CompiledQuery query : model.getQueries()) {
            if (query.isAggregate()) {
                out.add(new Requirement(StorageCapability.AGGREGATION_PIPELINE,
                        "query '" + query.name() + "'",
                        "it groups or aggregates, which the engine must do server-side -- pulling every "
                        + "row back to aggregate in Java does not scale and is not what the model says"));
            }
            for (CompiledGroupByField groupBy : query.groupBy()) {
                if (isJoin(groupBy.field())) {
                    out.add(new Requirement(StorageCapability.SERVER_SIDE_JOIN,
                            "query '" + query.name() + "' groupBy '" + groupBy.field() + "'",
                            "it groups by a field reached through a reference, which is a join"));
                }
            }
        }

        if (!model.getAggregates().isEmpty()) {
            // An aggregate is a root plus its collections, written and read as one unit.
            String names = model.getAggregates().stream()
                    .map(aggregate -> aggregate.name())
                    .collect(Collectors.joining(", "));
            out.add(new Requirement(StorageCapability.TRANSACTIONS, "aggregate(s) " + names,
                    "an aggregate's root and collections commit as ONE unit or the aggregate is a lie"));
        }

        return out;
    }

    private static boolean isJoin(String groupByField) {
        if (groupByField == null || groupByField.isBlank()) {
            return false;
        }
        try {
            return GroupByJoinGrammar.parse(groupByField) instanceof GroupByJoinGrammar.Target.Join;
        } catch (RuntimeException unsupportedPath) {
            // Not this gate's job to report a malformed path -- the DSL validator already refuses
            // those with a better message. Swallowing it here would be wrong only if it were the
            // ONLY check; it is not.
            return false;
        }
    }

    private static Map<StorageCapability, String> alternativesFor(Set<StorageCapability> missing) {
        Map<StorageCapability, String> out = new LinkedHashMap<>();
        for (StorageCapability capability : missing) {
            Set<String> able = new TreeSet<>();
            for (SqlDialect candidate : SqlDialects.all()) {
                if (candidate.capabilities().contains(capability)) {
                    able.add(candidate.name());
                }
            }
            out.put(capability, able.isEmpty() ? "(no registered engine supports this)" : String.join(", ", able));
        }
        return out;
    }

    /**
     * The refusal. Its message is the whole product of this gate, so it is built with care: every
     * unmet capability, every model element that needs it, and both ways out.
     */
    public static final class UnsupportedStorageEngineException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final transient Map<StorageCapability, List<Requirement>> unmet;

        UnsupportedStorageEngineException(String engineName, String dialectName,
                                          Map<StorageCapability, List<Requirement>> unmet,
                                          Map<StorageCapability, String> alternatives) {
            super(buildMessage(engineName, dialectName, unmet, alternatives));
            this.unmet = unmet;
        }

        private static String buildMessage(String engineName, String dialectName,
                                           Map<StorageCapability, List<Requirement>> unmet,
                                           Map<StorageCapability, String> alternatives) {
            StringBuilder message = new StringBuilder();
            message.append("This model cannot be generated for database.engine=").append(engineName)
                    .append(" (dialect '").append(dialectName).append("').\n");
            for (Map.Entry<StorageCapability, List<Requirement>> entry : unmet.entrySet()) {
                StorageCapability capability = entry.getKey();
                List<Requirement> requirements = entry.getValue();
                message.append("\n  ").append(engineName).append(" does not support ")
                        .append(capability).append(", which this model needs for:\n");
                for (Requirement requirement : requirements) {
                    message.append("    - ").append(requirement.element())
                            .append("  (").append(requirement.because()).append(")\n");
                }
                message.append("    Either use an engine that does (")
                        .append(alternatives.getOrDefault(capability, "?"))
                        .append("), or remove what needs it above.\n");
            }
            message.append("\nRefused at generation time on purpose: an app generated anyway would boot "
                    + "cleanly and lose this behaviour silently, which surfaces as missing or wrong data "
                    + "much later. Run `npdev doctor` for the full capability matrix.");
            return message.toString();
        }

        /** The unmet capabilities, for a caller that wants to render them rather than print them. */
        public Map<StorageCapability, List<Requirement>> unmet() {
            return unmet;
        }
    }

    /**
     * The capability matrix, delegated to {@link SqlDialects#capabilityMatrix()}.
     *
     * <p>It lives in the kernel so {@code npdev doctor} can render it with nothing but the staged
     * kernel jar -- a matrix a tool cannot print without a full generator build is a matrix that
     * quietly stops being printed. Kept reachable from here because this is the class a
     * generator-side reader looks in.
     */
    public static String capabilityMatrix() {
        return SqlDialects.capabilityMatrix();
    }
}
