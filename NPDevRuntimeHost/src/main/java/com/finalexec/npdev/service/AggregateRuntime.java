package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads a declared aggregate as a nested tree: the root record plus every owned
 * child collection, recursively, joined by each collection's {@code childField}.
 *
 * <p>P0 slice 2 of the Aggregate Workbench (ADR-0004). Read-only for now; the
 * draft patch/commit boundary arrives with P4/P6.
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
