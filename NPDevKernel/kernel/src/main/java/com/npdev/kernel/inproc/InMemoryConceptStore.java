package com.npdev.kernel.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import com.npdev.kernel.concepts.ReferentialIntegrityException;
import com.npdev.kernel.ports.ConceptStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class InMemoryConceptStore implements ConceptStore {
    private final Map<String, ConceptRecord> records = new LinkedHashMap<>();
    private final CompiledModel model;

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

    @Override
    public synchronized Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        return Optional.ofNullable(records.get(key(tenantId, conceptName, id)));
    }

    @Override
    public synchronized List<ConceptRecord> findAll(String tenantId, String conceptName) {
        String prefix = keyPrefix(tenantId, conceptName);
        List<ConceptRecord> out = new ArrayList<>();
        for (Map.Entry<String, ConceptRecord> entry : records.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                out.add(entry.getValue());
            }
        }
        out.sort(Comparator.comparing(ConceptRecord::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
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
        ConceptRecord current = records.get(key);
        long newVersion;
        if (record.rowVersion() == null) {
            newVersion = current == null ? 0L : current.rowVersion() + 1;
        } else {
            if (current == null || !record.rowVersion().equals(current.rowVersion())) {
                throw new ConceptStoreOptimisticLockException(
                        record.conceptName(), record.id(), record.tenantId(), Optional.ofNullable(current));
            }
            newVersion = record.rowVersion() + 1;
        }
        ConceptRecord toStore = new ConceptRecord(
                record.conceptName(), record.id(), record.tenantId(), record.data(), newVersion);
        records.put(key, toStore);
        return toStore;
    }

    @Override
    public synchronized void deleteById(String tenantId, String conceptName, String id) {
        if (model != null) {
            enforceReferentialIntegrity(tenantId, conceptName, id);
        }
        records.remove(key(tenantId, conceptName, id));
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
