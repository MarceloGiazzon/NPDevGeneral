package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads and commits a declared aggregate as a nested tree: the root record plus
 * every owned child collection, recursively, joined by each collection's
 * {@code childField}. Part of the Aggregate Workbench (ADR-0004 / ADR-0005).
 */
@Service
public class AggregateRuntime {

    private final CompiledModel compiledModel;
    private final ConceptGateway conceptGateway;

    @Autowired
    public AggregateRuntime(
            ObjectProvider<CompiledModel> compiledModel,
            ObjectProvider<ConceptGateway> conceptGateway
    ) {
        this(
                compiledModel == null ? null : compiledModel.getIfAvailable(),
                conceptGateway == null ? null : conceptGateway.getIfAvailable()
        );
    }

    public AggregateRuntime(CompiledModel compiledModel, ConceptGateway conceptGateway) {
        this.compiledModel = compiledModel;
        this.conceptGateway = conceptGateway;
    }

    /**
     * Load the aggregate named {@code aggregateName} rooted at {@code rootId} into a nested map:
     * {@code {aggregate, id, <root fields...>, <collectionName>: [ {id, ...fields, <nested>...} ] } }.
     *
     * @throws IllegalArgumentException if the aggregate is unknown or the root record is not found
     * @throws IllegalStateException    if no ConceptGateway is available
     */
    public Map<String, Object> load(String aggregateName, String rootId, ExecutionContext context) {
        CompiledAggregate aggregate = findAggregate(aggregateName);
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        ConceptGateway gateway = requireConceptGateway();

        Optional<ConceptRecord> root = gateway.read(
                new ConceptReadRequest(aggregate.root(), rootId, null), effectiveContext);
        if (root.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aggregate " + aggregate.name() + " root " + aggregate.root()
                            + " not found for id: " + rootId);
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("aggregate", aggregate.name());
        putRecord(tree, root.get());
        for (CompiledAggregateCollection collection : aggregate.collections()) {
            tree.put(collection.name(), loadCollection(collection, rootId, gateway, effectiveContext));
        }
        return tree;
    }

    /**
     * Commit a draft aggregate tree: upsert the root and every owned child (assigning each child's
     * {@code childField} to its parent id), and delete any currently-persisted child no longer present
     * in the draft (reconcile, cascading down the tree). New rows without an {@code id} are assigned one.
     * Returns the freshly re-loaded tree.
     *
     * @throws IllegalArgumentException if the aggregate is unknown
     * @throws IllegalStateException    if no ConceptGateway is available
     */
    public Map<String, Object> commit(String aggregateName, Map<String, Object> draft, ExecutionContext context) {
        CompiledAggregate aggregate = findAggregate(aggregateName);
        ExecutionContext ctx = context == null ? ExecutionContext.anonymous() : context;
        ConceptGateway gateway = requireConceptGateway();
        Map<String, Object> rootDraft = draft == null ? Map.of() : draft;

        String rootId = idOrNew(rootDraft.get("id"));
        Set<String> rootCollectionKeys = collectionNames(aggregate.collections());
        Map<String, Object> rootFields = scalarFields(rootDraft, rootCollectionKeys, null, null);
        rootFields.put("id", rootId); // the gateway requires the id field present in the write payload
        gateway.save(new ConceptWriteRequest(aggregate.root(), rootId, ctx.tenantId(), rootFields), ctx);

        commitCollections(aggregate.collections(), rootDraft, rootId, gateway, ctx);
        return load(aggregate.name(), rootId, ctx);
    }

    private void commitCollections(
            List<CompiledAggregateCollection> collections,
            Map<String, Object> parentDraft,
            String parentId,
            ConceptGateway gateway,
            ExecutionContext ctx
    ) {
        for (CompiledAggregateCollection collection : collections) {
            List<Map<String, Object>> draftRows = asRowList(parentDraft.get(collection.name()));
            Set<String> grandKeys = collectionNames(collection.collections());
            Set<String> keptIds = new LinkedHashSet<>();
            for (Map<String, Object> row : draftRows) {
                String childId = idOrNew(row.get("id"));
                keptIds.add(childId);
                Map<String, Object> fields = scalarFields(row, grandKeys, collection.childField(), parentId);
                fields.put("id", childId); // the gateway requires the id field present in the write payload
                gateway.save(new ConceptWriteRequest(collection.concept(), childId, ctx.tenantId(), fields), ctx);
                commitCollections(collection.collections(), row, childId, gateway, ctx);
            }
            // Reconcile: delete persisted children of this parent that are absent from the draft.
            List<ConceptRecord> current = gateway.list(
                    new ConceptListRequest(collection.concept(), null, collection.childField(), parentId), ctx);
            for (ConceptRecord existing : current) {
                if (!keptIds.contains(existing.id())) {
                    gateway.delete(new ConceptReadRequest(collection.concept(), existing.id(), null), ctx);
                }
            }
        }
    }

    /** The row's scalar fields to persist: drop id/aggregate/child-collection keys; set childField=parentId. */
    private static Map<String, Object> scalarFields(
            Map<String, Object> row, Set<String> collectionKeys, String childField, String parentId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key.equals("id") || key.equals("aggregate") || key.equals("__children")
                    || collectionKeys.contains(normalize(key))) {
                continue;
            }
            fields.put(key, entry.getValue());
        }
        if (childField != null && !childField.isBlank()) {
            fields.put(childField, parentId);
        }
        return fields;
    }

    private static Set<String> collectionNames(List<CompiledAggregateCollection> collections) {
        Set<String> names = new LinkedHashSet<>();
        for (CompiledAggregateCollection collection : collections) {
            names.add(normalize(collection.name()));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRowList(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
        }
        return rows;
    }

    private static String idOrNew(Object value) {
        String id = value == null ? null : String.valueOf(value);
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<Map<String, Object>> loadCollection(
            CompiledAggregateCollection collection,
            String parentId,
            ConceptGateway gateway,
            ExecutionContext context
    ) {
        List<ConceptRecord> children = gateway.list(
                new ConceptListRequest(collection.concept(), null, collection.childField(), parentId),
                context);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConceptRecord child : children) {
            Map<String, Object> row = new LinkedHashMap<>();
            putRecord(row, child);
            for (CompiledAggregateCollection nested : collection.collections()) {
                row.put(nested.name(), loadCollection(nested, child.id(), gateway, context));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Flatten a record into the row map: id at the top, then its data fields. */
    private static void putRecord(Map<String, Object> target, ConceptRecord record) {
        target.put("id", record.id());
        target.putAll(record.data());
    }

    private CompiledAggregate findAggregate(String aggregateName) {
        if (aggregateName == null || aggregateName.isBlank()) {
            throw new IllegalArgumentException("aggregate name is required");
        }
        if (compiledModel == null) {
            throw new IllegalStateException("Compiled model is not configured.");
        }
        String normalized = aggregateName.trim().toLowerCase(Locale.ROOT);
        return compiledModel.getAggregates().stream()
                .filter(aggregate -> aggregate.name() != null
                        && aggregate.name().trim().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Aggregate not found: " + aggregateName));
    }

    private ConceptGateway requireConceptGateway() {
        if (conceptGateway == null) {
            throw new IllegalStateException("ConceptGateway is required for aggregate data.");
        }
        return conceptGateway;
    }
}
