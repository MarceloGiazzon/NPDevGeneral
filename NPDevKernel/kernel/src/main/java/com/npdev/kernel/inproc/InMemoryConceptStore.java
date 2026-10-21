package com.npdev.kernel.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.kernel.concepts.ConceptAggregateEngine;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import com.npdev.kernel.concepts.ConceptStoreUniqueViolationException;
import com.npdev.kernel.concepts.ReferentialIntegrityException;
import com.npdev.kernel.ports.ConceptStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class InMemoryConceptStore implements ConceptStore {
    private final Map<String, ConceptRecord> records = new LinkedHashMap<>();
    // Soft delete (concepts declaring softDelete: true): a deleted row stays in `records` but its key
    // is listed here, and every read below treats a listed key as absent -- exactly what the JDBC
    // store's deletedAt column does -- so restore() can bring it back. Guarded by `this`.
    private final Set<String> softDeletedKeys = new LinkedHashSet<>();
    // REG-267: per-thread undo journal, non-null only while that thread is inside runAtomically.
    private final ThreadLocal<List<Undo>> journal = new ThreadLocal<>();

    /** Before-image of one key, enough to put the store back exactly as it was. */
    private record Undo(String key, ConceptRecord previous, boolean wasSoftDeleted) {
    }
    // REG-208 (B28 lift): volatile, not final -- setModel lets a RuntimeHost-level reload listener
    // (ModelHolder lives in a module this class must not depend on) point this store at a newly
    // reloaded model IN PLACE, preserving every already-stored record. Only the delete-cascade
    // logic below reads this reference; nothing here validates stored data against it, so swapping
    // it never touches or invalidates existing records.
    private volatile CompiledModel model;

    public InMemoryConceptStore() {
        this(null);
    }

    /**
     * @param model when present, deletes are checked against every other concept's declared
     *              {@code reference}/{@code onDelete} bonds pointing at the concept being deleted
     *              (restrict/cascade/nullify), mirroring the referential-integrity enforcement a
     *              physical database's foreign-key constraints already provide for JDBC-backed
     *              stores. {@code null} preserves the original no-enforcement behavior.
     */
    public InMemoryConceptStore(CompiledModel model) {
        this.model = model;
    }

    /** REG-208 (B28 lift): repoints this store at a newly reloaded model, in place -- see the field's
     * own javadoc for why this never touches already-stored records. */
    public void setModel(CompiledModel model) {
        this.model = model;
    }

    @Override
    public synchronized Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        return Optional.ofNullable(live(key(tenantId, conceptName, id)));
    }

    /** The row under {@code key}, or null when absent or soft-deleted. */
    private ConceptRecord live(String key) {
        return softDeletedKeys.contains(key) ? null : records.get(key);
    }

    @Override
    public synchronized List<ConceptRecord> findAll(String tenantId, String conceptName) {
        String prefix = keyPrefix(tenantId, conceptName);
        List<ConceptRecord> out = new ArrayList<>();
        for (Map.Entry<String, ConceptRecord> entry : records.entrySet()) {
            if (entry.getKey().startsWith(prefix) && !softDeletedKeys.contains(entry.getKey())) {
                out.add(entry.getValue());
            }
        }
        out.sort(Comparator.comparing(ConceptRecord::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    /**
     * S4 (roadmap B27, ADR-0011 D1): overrides {@link ConceptStore}'s default aggregate (which only
     * ever fetches ONE concept's rows) so a {@code groupBy} join hop can pre-materialize the joined
     * value BEFORE handing rows to {@link ConceptAggregateEngine} -- which stays completely
     * unmodified, since {@code record.data().get(groupByField.field())} already works for ANY key,
     * including a join path's own raw string, once that key is actually present in the row.
     *
     * <p>INNER JOIN semantics, to agree with {@code JdbcBusinessConceptStore}'s real SQL JOIN: a base
     * record whose reference field is null, or whose referenced row does not exist for this tenant,
     * contributes nothing and is excluded from the aggregate entirely -- never grouped under a
     * {@code null} key, which would silently disagree with the JDBC engine's row count.
     *
     * <p>Requires {@code model} (the constructor that omits it can't resolve a reference field's
     * target concept) -- a join-shaped {@code groupBy} against a no-model store is refused loudly
     * (X0), not silently evaluated as if every row failed to join.
     *
     * <p>S8 W1.1 (roadmap deferred item #1): a join may chain up to
     * {@link GroupByJoinGrammar#MAX_JOIN_HOPS} hops. {@code hopTargetConceptsByField} pre-resolves,
     * for each distinct {@code groupBy} join's raw field string, the concept name at EACH hop
     * position (walking from {@code conceptName} through {@code join.referenceFields()} in order);
     * the row loop below then walks the SAME chain per record, following one reference field at a
     * time, so an INNER-JOIN miss at any hop (not just the first) excludes the row -- identically to
     * a real SQL chained {@code JOIN}.
     */
    @Override
    public synchronized ConceptAggregateResult aggregate(String tenantId, String conceptName, ConceptAggregateQuery query) {
        List<ConceptRecord> baseRecords = findAll(tenantId, conceptName);

        Map<String, GroupByJoinGrammar.Target.Join> joinsByField = new LinkedHashMap<>();
        for (ConceptAggregateQuery.GroupByField groupByField : query.groupBy()) {
            if (GroupByJoinGrammar.parse(groupByField.field()) instanceof GroupByJoinGrammar.Target.Join join) {
                joinsByField.put(groupByField.field(), join);
            }
        }
        if (joinsByField.isEmpty()) {
            return ConceptAggregateEngine.apply(baseRecords, query);
        }
        if (model == null) {
            throw new IllegalStateException(
                    "Cannot resolve groupBy join field(s) " + joinsByField.keySet() + " on concept " + conceptName
                            + " -- this InMemoryConceptStore was built without a CompiledModel "
                            + "(see InMemoryConceptStore(CompiledModel)), so a reference field's join "
                            + "target cannot be looked up");
        }

        Map<String, List<String>> hopTargetConceptsByField = new LinkedHashMap<>();
        Set<String> touchedConcepts = new LinkedHashSet<>();
        for (Map.Entry<String, GroupByJoinGrammar.Target.Join> entry : joinsByField.entrySet()) {
            List<String> hopTargets = new ArrayList<>();
            String currentConceptName = conceptName;
            for (String referenceField : entry.getValue().referenceFields()) {
                String target = referenceTargetOf(currentConceptName, referenceField);
                if (target == null) {
                    throw new IllegalArgumentException(
                            "groupBy join field '" + referenceField + "' on concept " + currentConceptName
                                    + " is not a declared reference field -- the compile-time validator "
                                    + "(PackValidation#validateAggregateQuery) should have refused this model "
                                    + "before it ever reached the store");
                }
                hopTargets.add(target);
                touchedConcepts.add(target);
                currentConceptName = target;
            }
            hopTargetConceptsByField.put(entry.getKey(), hopTargets);
        }

        Map<String, Map<String, ConceptRecord>> targetRecordsById = new LinkedHashMap<>();
        for (String targetConcept : touchedConcepts) {
            Map<String, ConceptRecord> byId = new LinkedHashMap<>();
            for (ConceptRecord record : findAll(tenantId, targetConcept)) {
                byId.put(normalize(record.id()), record);
            }
            targetRecordsById.put(targetConcept, byId);
        }

        List<ConceptRecord> joined = new ArrayList<>();
        for (ConceptRecord base : baseRecords) {
            Map<String, Object> augmented = null;
            boolean joinable = true;
            for (ConceptAggregateQuery.GroupByField groupByField : query.groupBy()) {
                GroupByJoinGrammar.Target.Join join = joinsByField.get(groupByField.field());
                if (join == null) {
                    continue;
                }
                List<String> hopTargets = hopTargetConceptsByField.get(groupByField.field());
                ConceptRecord current = base;
                for (int hop = 0; hop < join.referenceFields().size(); hop++) {
                    Object fkValue = current.data().get(join.referenceFields().get(hop));
                    ConceptRecord targetRecord = fkValue == null ? null
                            : targetRecordsById.get(hopTargets.get(hop)).get(normalize(String.valueOf(fkValue)));
                    if (targetRecord == null) {
                        joinable = false;
                        break;
                    }
                    current = targetRecord;
                }
                if (!joinable) {
                    break;
                }
                if (augmented == null) {
                    augmented = new LinkedHashMap<>(base.data());
                }
                augmented.put(groupByField.field(), current.data().get(join.targetField()));
            }
            if (!joinable) {
                continue;
            }
            joined.add(augmented == null ? base
                    : new ConceptRecord(base.conceptName(), base.id(), base.tenantId(), augmented, base.rowVersion()));
        }

        return ConceptAggregateEngine.apply(joined, query);
    }

    /** Looks up {@code referenceField}'s declared {@code reference.target} on {@code conceptName},
     *  or null if the concept/field is unknown or the field isn't a reference at all. */
    private String referenceTargetOf(String conceptName, String referenceField) {
        for (CompiledConcept concept : model.getConcepts()) {
            if (!concept.getName().equalsIgnoreCase(conceptName)) {
                continue;
            }
            for (CompiledField field : concept.getFields()) {
                if (field.getName().equalsIgnoreCase(referenceField)) {
                    return field.getReferenceTarget();
                }
            }
        }
        return null;
    }

    /**
     * LNCH-16: {@code record.rowVersion() == null} is an unconditional write (today's behavior) --
     * used for both creates and force-writes -- but the stored version is still tracked/incremented
     * so a later caller can start doing real compare-and-swap against it. A non-null rowVersion is
     * a compare-and-swap request: it must match the currently-stored version (0 rows / no row =
     * conflict), and succeeds by storing {@code rowVersion + 1}. {@code synchronized} on this whole
     * method (like every other method here) makes the read-compare-write atomic for free.
     */
    @Override
    public synchronized ConceptRecord save(ConceptRecord record) {
        String key = key(record.tenantId(), record.conceptName(), record.id());
        ConceptRecord stored = records.get(key);
        ConceptRecord current = live(key);
        long newVersion;
        if (record.rowVersion() == null) {
            newVersion = stored == null ? 0L : stored.rowVersion() + 1;
        } else {
            if (current == null || !record.rowVersion().equals(current.rowVersion())) {
                throw new ConceptStoreOptimisticLockException(
                        record.conceptName(), record.id(), record.tenantId(), Optional.ofNullable(current));
            }
            newVersion = record.rowVersion() + 1;
        }
        ConceptRecord toStore = new ConceptRecord(
                record.conceptName(), record.id(), record.tenantId(), record.data(), newVersion);
        enforceUniqueIndexes(toStore, key);
        remember(key);
        records.put(key, toStore);
        softDeletedKeys.remove(key);
        return toStore;
    }

    /**
     * REG-257: a concept's declared {@code unique} index (single or compound) is enforced here, the
     * in-memory counterpart of the UNIQUE index the schema emits for a database. Same collision rule
     * as every other unique check ({@link ConceptStore#uniqueValuesCollide}: a null never collides,
     * strings compare trimmed and case-insensitively), against LIVE rows only -- a soft-deleted row
     * frees its value. A no-model store knows no indexes and enforces nothing.
     */
    private void enforceUniqueIndexes(ConceptRecord candidate, String candidateKey) {
        CompiledModel current = model;
        if (current == null) {
            return;
        }
        CompiledConcept concept = findConcept(current, candidate.conceptName());
        if (concept == null || concept.getIndexes() == null) {
            return;
        }
        for (CompiledIndex index : concept.getIndexes()) {
            if (!index.isUnique() || index.getFields().isEmpty()) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            boolean anyNull = false;
            for (String field : index.getFields()) {
                Object value = valueOf(candidate.data(), field);
                if (value == null) {
                    anyNull = true;
                    break;
                }
                values.add(value);
            }
            if (anyNull) {
                continue;
            }
            String prefix = keyPrefix(candidate.tenantId(), candidate.conceptName());
            for (Map.Entry<String, ConceptRecord> entry : records.entrySet()) {
                if (!entry.getKey().startsWith(prefix) || entry.getKey().equals(candidateKey)
                        || softDeletedKeys.contains(entry.getKey())) {
                    continue;
                }
                boolean allMatch = true;
                for (int i = 0; i < values.size(); i++) {
                    if (!ConceptStore.uniqueValuesCollide(
                            valueOf(entry.getValue().data(), index.getFields().get(i)), values.get(i))) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    throw new ConceptStoreUniqueViolationException(
                            candidate.conceptName(), index.getName(), index.getFields());
                }
            }
        }
    }

    private static Object valueOf(Map<String, Object> data, String field) {
        if (data.containsKey(field)) {
            return data.get(field);
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(field)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static CompiledConcept findConcept(CompiledModel model, String conceptName) {
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept.getName().equalsIgnoreCase(conceptName)) {
                return concept;
            }
        }
        return null;
    }

    private boolean isSoftDelete(String conceptName) {
        CompiledModel current = model;
        if (current == null) {
            return false;
        }
        CompiledConcept concept = findConcept(current, conceptName);
        return concept != null && concept.isSoftDelete();
    }

    /** Records {@code key}'s current state in the calling thread's undo journal, if it has one. */
    private void remember(String key) {
        List<Undo> active = journal.get();
        if (active != null) {
            active.add(new Undo(key, records.get(key), softDeletedKeys.contains(key)));
        }
    }

    /**
     * REG-267: undo journal. Every write made by the calling thread inside {@code work} is recorded
     * as a before-image; if {@code work} throws, they are replayed newest-first and the exception
     * propagates, so a failing unit leaves the store exactly as it found it. Nested calls join the
     * outer unit. There is no isolation (other threads see the writes while the unit runs); this is
     * the in-process development store, which has no database to provide it.
     */
    @Override
    public <T> T runAtomically(Supplier<T> work) {
        if (journal.get() != null) {
            return work.get();
        }
        List<Undo> entries = new ArrayList<>();
        journal.set(entries);
        try {
            return work.get();
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Undo undo = entries.get(i);
                    if (undo.previous() == null) {
                        records.remove(undo.key());
                    } else {
                        records.put(undo.key(), undo.previous());
                    }
                    if (undo.wasSoftDeleted()) {
                        softDeletedKeys.add(undo.key());
                    } else {
                        softDeletedKeys.remove(undo.key());
                    }
                }
            }
            throw failure;
        } finally {
            journal.remove();
        }
    }

    @Override
    public synchronized boolean restore(String tenantId, String conceptName, String id) {
        String key = key(tenantId, conceptName, id);
        if (!softDeletedKeys.contains(key) || !records.containsKey(key)) {
            return false;
        }
        // The restored row becomes live again, so it must not collide with a row that took its unique
        // value while it was deleted.
        enforceUniqueIndexes(records.get(key), key);
        remember(key);
        softDeletedKeys.remove(key);
        return true;
    }

    @Override
    public synchronized void deleteById(String tenantId, String conceptName, String id, Long expectedRowVersion) {
        // REG-210: compare-and-swap on the delete -- the version is checked BEFORE any side effect
        // (referential-integrity cascades in the 3-arg form below), so a CAS loser never triggers a
        // cascade on a row it did not actually delete.
        if (expectedRowVersion != null) {
            ConceptRecord current = live(key(tenantId, conceptName, id));
            if (current == null || !expectedRowVersion.equals(current.rowVersion())) {
                throw new ConceptStoreOptimisticLockException(
                        conceptName, id, tenantId, Optional.ofNullable(current));
            }
        }
        deleteById(tenantId, conceptName, id);
    }

    @Override
    public synchronized void deleteById(String tenantId, String conceptName, String id) {
        if (model != null) {
            enforceReferentialIntegrity(tenantId, conceptName, id);
        }
        String key = key(tenantId, conceptName, id);
        if (isSoftDelete(conceptName)) {
            if (live(key) != null) {
                remember(key);
                softDeletedKeys.add(key);
            }
            return;
        }
        remember(key);
        records.remove(key);
        softDeletedKeys.remove(key);
    }

    /**
     * Scans every other concept's fields for a {@code reference} targeting {@code conceptName}
     * and applies its {@code onDelete} policy (default {@code restrict}, matching
     * {@link CompiledReferenceSemantics#getOnDelete()}'s own documented default) against whatever
     * rows in this same in-memory registry currently point at {@code id}.
     */
    private void enforceReferentialIntegrity(String tenantId, String conceptName, String id) {
        for (CompiledConcept referencingConcept : model.getConcepts()) {
            for (CompiledField field : referencingConcept.getFields()) {
                if (!conceptName.equals(field.getReferenceTarget())) {
                    continue;
                }
                String onDelete = onDeletePolicy(field.getReferenceSemantics());
                List<ConceptRecord> referencingRows = findReferencingRows(
                        tenantId, referencingConcept.getName(), field.getName(), id);
                if (referencingRows.isEmpty()) {
                    continue;
                }
                switch (onDelete) {
                    case "cascade" -> {
                        for (ConceptRecord row : referencingRows) {
                            deleteById(tenantId, referencingConcept.getName(), row.id());
                        }
                    }
                    case "nullify" -> {
                        for (ConceptRecord row : referencingRows) {
                            Map<String, Object> updatedData = new LinkedHashMap<>(row.data());
                            updatedData.put(field.getName(), null);
                            save(new ConceptRecord(row.conceptName(), row.id(), row.tenantId(), updatedData));
                        }
                    }
                    default -> throw new ReferentialIntegrityException(
                            conceptName,
                            field.getName(),
                            "Cannot delete " + conceptName + " " + id + ": referenced by "
                                    + referencingRows.size() + " " + referencingConcept.getName()
                                    + " record(s) via '" + field.getName() + "'"
                    );
                }
            }
        }
    }

    private static String onDeletePolicy(CompiledReferenceSemantics semantics) {
        if (semantics == null || semantics.getOnDelete() == null || semantics.getOnDelete().isBlank()) {
            return "restrict";
        }
        return semantics.getOnDelete().trim().toLowerCase(Locale.ROOT);
    }

    private List<ConceptRecord> findReferencingRows(String tenantId, String conceptName, String fieldName, String id) {
        List<ConceptRecord> out = new ArrayList<>();
        for (ConceptRecord record : findAll(tenantId, conceptName)) {
            Object value = record.data().get(fieldName);
            if (value != null && id.equals(String.valueOf(value))) {
                out.add(record);
            }
        }
        return out;
    }

    private static String key(String tenantId, String conceptName, String id) {
        return keyPrefix(tenantId, conceptName) + normalize(id);
    }

    private static String keyPrefix(String tenantId, String conceptName) {
        return normalize(tenantId) + "|" + normalize(conceptName) + "|";
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
