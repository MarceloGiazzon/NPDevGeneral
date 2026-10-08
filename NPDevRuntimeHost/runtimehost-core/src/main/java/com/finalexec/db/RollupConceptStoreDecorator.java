package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRollup;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * P8 prelude: keeps every concept's declared {@code rollups[]} (e.g. {@code Mosaic.likeCount =
 * count(Like via mosaicId)}) equal to the aggregate it names. Wraps the application's ONE
 * {@link ConceptStore} bean, the funnel generated CRUD, flow {@code createConcept} steps, aggregate
 * commits and agent writes all end in -- so no write path can skip it.
 *
 * <ul>
 *   <li>A child save/delete/restore recomputes the parent it points at (and, on a save that moved
 *       the child, the parent it used to point at) in the same thread/transaction. The parent row
 *       is locked with {@link ConceptStore#findByIdForUpdate} first, so two concurrent likes
 *       serialize on it and the second count sees the first.</li>
 *   <li>The value is written with {@link ConceptStore#writeMaintainedFields} -- never a versioned
 *       save -- so a like does not turn the owner's open edit into an optimistic-lock conflict.</li>
 *   <li>A save of the parent itself overwrites the rollup fields with freshly computed values, so a
 *       client cannot write them and a stale form cannot roll them back.</li>
 * </ul>
 *
 * <p>Concepts with no rollups in either direction pay one map lookup per write.</p>
 */
public final class RollupConceptStoreDecorator implements ConceptStore {
    private static final String VALUE_KEY = "value";

    private final ConceptStore delegate;
    private final Supplier<CompiledModel> model;
    private volatile Index index = Index.EMPTY;

    public RollupConceptStoreDecorator(ConceptStore delegate, Supplier<CompiledModel> model) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public ConceptRecord save(ConceptRecord record) {
        if (record == null) {
            return delegate.save(null);
        }
        Index current = index();
        List<Sourced> sourced = current.bySource(record.conceptName());
        List<CompiledRollup> own = current.byParent(record.conceptName());
        if (sourced.isEmpty() && own.isEmpty()) {
            return delegate.save(record);
        }
        ConceptRecord toSave = record;
        if (!own.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>(record.data());
            for (CompiledRollup rollup : own) {
                data.put(rollup.field(), compute(record.tenantId(), record.id(), rollup));
            }
            toSave = new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), data, record.rowVersion());
        }
        Optional<ConceptRecord> previous = sourced.isEmpty()
                ? Optional.empty()
                : delegate.findById(record.tenantId(), record.conceptName(), record.id());
        ConceptRecord saved = delegate.save(toSave);
        for (Sourced entry : sourced) {
            String before = previous.map(p -> parentId(p, entry.rollup().via())).orElse(null);
            String after = parentId(saved, entry.rollup().via());
            recompute(record.tenantId(), entry, after);
            if (before != null && !before.equalsIgnoreCase(after == null ? "" : after)) {
                recompute(record.tenantId(), entry, before);
            }
        }
        return saved;
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        List<Sourced> sourced = index().bySource(conceptName);
        Optional<ConceptRecord> previous = sourced.isEmpty() ? Optional.empty() : delegate.findById(tenantId, conceptName, id);
        delegate.deleteById(tenantId, conceptName, id);
        recomputeParentsOf(tenantId, sourced, previous);
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id, Long expectedRowVersion) {
        List<Sourced> sourced = index().bySource(conceptName);
        Optional<ConceptRecord> previous = sourced.isEmpty() ? Optional.empty() : delegate.findById(tenantId, conceptName, id);
        delegate.deleteById(tenantId, conceptName, id, expectedRowVersion);
        recomputeParentsOf(tenantId, sourced, previous);
    }

    @Override
    public boolean restore(String tenantId, String conceptName, String id) {
        boolean restored = delegate.restore(tenantId, conceptName, id);
        if (restored) {
            List<Sourced> sourced = index().bySource(conceptName);
            if (!sourced.isEmpty()) {
                recomputeParentsOf(tenantId, sourced, delegate.findById(tenantId, conceptName, id));
            }
        }
        return restored;
    }

    @Override
    public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        return delegate.findById(tenantId, conceptName, id);
    }

    @Override
    public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
        return delegate.findByIdForUpdate(tenantId, conceptName, id);
    }

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        return delegate.findAll(tenantId, conceptName);
    }

    @Override
    public ConceptListSlice<ConceptRecord> findAllCapped(String tenantId, String conceptName, int maxRows) {
        return delegate.findAllCapped(tenantId, conceptName, maxRows);
    }

    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        return delegate.query(tenantId, conceptName, query);
    }

    @Override
    public ConceptAggregateResult aggregate(String tenantId, String conceptName, ConceptAggregateQuery query) {
        return delegate.aggregate(tenantId, conceptName, query);
    }

    @Override
    public boolean existsUnique(String tenantId, String conceptName, List<String> fieldNames, List<Object> values, String excludeId) {
        return delegate.existsUnique(tenantId, conceptName, fieldNames, values, excludeId);
    }

    @Override
    public void writeMaintainedFields(String tenantId, String conceptName, String id, Map<String, Object> values) {
        delegate.writeMaintainedFields(tenantId, conceptName, id, values);
    }

    /**
     * Boot-time backfill: a parent whose rollup field is still NULL -- it predates the rollup, or the
     * column was just added -- is recomputed once, so a {@code likeCount} reads 0 (or the real count)
     * before any Like ever touches it. Ids are collected before anything is written, so an
     * empty-set min/max/avg that legitimately stays NULL cannot keep a page from advancing.
     *
     * @return how many parent rows were examined
     */
    public int backfill(String tenantId) {
        int examined = 0;
        for (List<Sourced> entries : index().sources().values()) {
            for (Sourced entry : entries) {
                List<String> ids = new ArrayList<>();
                ConceptQuery.Filter isNull = ConceptQuery.Filter.isNull(entry.rollup().field());
                for (int offset = 0; ; offset += ConceptQuery.MAX_LIMIT) {
                    ConceptPage page = delegate.query(tenantId, entry.parentConcept(),
                            new ConceptQuery(List.of(isNull), List.of(), offset, ConceptQuery.MAX_LIMIT));
                    page.items().forEach(parent -> ids.add(parent.id()));
                    if (!page.hasMore() || page.items().isEmpty()) {
                        break;
                    }
                }
                for (String id : ids) {
                    recompute(tenantId, entry, id);
                }
                examined += ids.size();
            }
        }
        return examined;
    }

    private void recomputeParentsOf(String tenantId, List<Sourced> sourced, Optional<ConceptRecord> child) {
        if (child.isEmpty()) {
            return;
        }
        for (Sourced entry : sourced) {
            recompute(tenantId, entry, parentId(child.get(), entry.rollup().via()));
        }
    }

    private void recompute(String tenantId, Sourced entry, String parentId) {
        if (parentId == null) {
            return;
        }
        Optional<ConceptRecord> parent = delegate.findByIdForUpdate(tenantId, entry.parentConcept(), parentId);
        if (parent.isEmpty()) {
            return;
        }
        Object value = compute(tenantId, parentId, entry.rollup());
        Object stored = valueOf(parent.get().data(), entry.rollup().field());
        if (!numericallyEqual(stored, value)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(entry.rollup().field(), value);
            delegate.writeMaintainedFields(tenantId, entry.parentConcept(), parent.get().id(), values);
        }
    }

    /** count and sum of no rows are 0 (a counter is never null); min/max/avg of no rows are null. */
    private Object compute(String tenantId, String parentId, CompiledRollup rollup) {
        String fn = rollup.fn().toLowerCase(Locale.ROOT);
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(ConceptQuery.Filter.eq(rollup.via(), parentId)),
                List.of(),
                List.of(new ConceptAggregateQuery.AggregateFunction(VALUE_KEY, fn, "count".equals(fn) ? null : rollup.of())),
                List.of(),
                List.of(),
                null
        );
        List<Map<String, Object>> rows = delegate.aggregate(tenantId, rollup.from(), query).rows();
        Object value = rows.isEmpty() ? null : rows.get(0).get(VALUE_KEY);
        if (value == null && ("count".equals(fn) || "sum".equals(fn))) {
            return 0L;
        }
        return value;
    }

    private static String parentId(ConceptRecord record, String via) {
        Object value = valueOf(record.data(), via);
        if (value instanceof Map<?, ?> embedded) {
            value = embedded.get("id");
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Object valueOf(Map<String, Object> data, String field) {
        if (data.containsKey(field)) {
            return data.get(field);
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(field)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean numericallyEqual(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        try {
            return new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right))) == 0;
        } catch (NumberFormatException notNumeric) {
            return String.valueOf(left).equals(String.valueOf(right));
        }
    }

    private Index index() {
        CompiledModel current = model.get();
        Index cached = index;
        if (cached.model() == current) {
            return cached;
        }
        Index rebuilt = Index.of(current);
        index = rebuilt;
        return rebuilt;
    }

    private record Sourced(String parentConcept, CompiledRollup rollup) {
    }

    private record Index(CompiledModel model, Map<String, List<Sourced>> sources, Map<String, List<CompiledRollup>> parents) {
        static final Index EMPTY = new Index(null, Map.of(), Map.of());

        static Index of(CompiledModel model) {
            if (model == null) {
                return EMPTY;
            }
            Map<String, List<Sourced>> sources = new LinkedHashMap<>();
            Map<String, List<CompiledRollup>> parents = new LinkedHashMap<>();
            for (CompiledConcept concept : model.getConcepts()) {
                for (CompiledRollup rollup : concept.getRollups()) {
                    sources.computeIfAbsent(key(rollup.from()), ignored -> new ArrayList<>())
                            .add(new Sourced(concept.getName(), rollup));
                    parents.computeIfAbsent(key(concept.getName()), ignored -> new ArrayList<>()).add(rollup);
                }
            }
            return new Index(model, Map.copyOf(sources), Map.copyOf(parents));
        }

        List<Sourced> bySource(String conceptName) {
            return sources.getOrDefault(key(conceptName), List.of());
        }

        List<CompiledRollup> byParent(String conceptName) {
            return parents.getOrDefault(key(conceptName), List.of());
        }

        private static String key(String conceptName) {
            return conceptName == null ? "" : conceptName.trim().toLowerCase(Locale.ROOT);
        }
    }
}
