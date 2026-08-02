package com.npdev.kernel.properties;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.AuditLogStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RC-A3's reference implementation of {@link PropertyResolver}, reading/writing the
 * {@code workspace::PropertyValue} concept (RC-A2) generically through {@link ConceptGateway} --
 * tenant isolation, permission checks, and row-level scoping all come for free from whichever
 * gateway is injected, exactly like every other RuntimeHost service built on it.
 *
 * <p>Vector 15: resolutions are cached per (tenantId, propertyKey, resolved scope-key tuple,
 * {@code version}) -- {@code version} is a single counter bumped on every {@link #set}, so a write
 * invalidates every cached resolution at once rather than needing per-key eviction. Read this file's
 * own concurrency test ({@code PropertyResolverCacheConcurrencyTest}) for the proof, not just the
 * shape: a cache that merely compiles is not the same claim as one proven safe under concurrent
 * reads racing a write.
 */
public final class DefaultPropertyResolver implements PropertyResolver {

    private static final String CONCEPT_NAME = "workspace::PropertyValue";
    private static final String AUDIT_RESOURCE_TYPE = "PROPERTY";
    private static final String AUDIT_ACTION_WRITE = "PROPERTY_WRITE";

    private final ConceptGateway conceptGateway;
    private final AuditLogStore auditLogStore;
    private final CompiledModel compiledModel;
    private final AtomicLong version = new AtomicLong();
    private final Map<CacheKey, PropertyExplanation> cache = new ConcurrentHashMap<>();

    public DefaultPropertyResolver(ConceptGateway conceptGateway, AuditLogStore auditLogStore, CompiledModel compiledModel) {
        this.conceptGateway = Objects.requireNonNull(conceptGateway, "conceptGateway");
        this.auditLogStore = Objects.requireNonNull(auditLogStore, "auditLogStore");
        this.compiledModel = Objects.requireNonNull(compiledModel, "compiledModel");
    }

    @Override
    public Object resolve(String propertyKey, ExecutionContext context) {
        return explain(propertyKey, context).value();
    }

    @Override
    public PropertyExplanation explain(String propertyKey, ExecutionContext context) {
        CompiledProperty property = declaredProperty(propertyKey);
        List<CompiledPropertyScope> scopes = compiledModel.getPropertyScopes();

        // Resolve every declared scope's concrete id ONCE (from ExecutionContext.tags -- never a
        // per-read DB lookup), in cascade order (most specific first, per compiledModel's own order).
        List<String> resolvedScopeIds = new ArrayList<>(scopes.size());
        for (CompiledPropertyScope scope : scopes) {
            resolvedScopeIds.add(resolveScopeId(scope, context));
        }

        // The cache key snapshots `version` BEFORE doing any gateway work: a `set()` that lands
        // between this read and the cache.put below simply produces a key nobody will ever look up
        // again (the next explain() call reads the bumped version), not a stale hit -- see the
        // concurrency test for the race this shape was chosen to survive.
        // NOT List.copyOf(): a scope the context cannot supply resolves to a genuine null (vector 12),
        // and List.copyOf/List.of both reject null elements.
        List<String> scopeKeyTuple = java.util.Collections.unmodifiableList(new ArrayList<>(resolvedScopeIds));
        CacheKey cacheKey = new CacheKey(context.tenantId(), propertyKey, scopeKeyTuple, version.get());
        PropertyExplanation cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        int winningIndex = -1;
        Object winningRawValue = null;
        for (int i = 0; i < scopes.size(); i++) {
            String scopeId = resolvedScopeIds.get(i);
            if (scopeId == null) {
                // Vector 12: a scope the context cannot supply (e.g. a null tag) is SKIPPED, not an
                // error -- that is a compile-time concern (PropertyValidation's FROM_GRAMMAR), not a
                // read-time one.
                continue;
            }
            Optional<ConceptRecord> row = findRow(scopes.get(i).name(), scopeId, propertyKey, context);
            if (row.isPresent()) {
                winningIndex = i;
                winningRawValue = row.get().data().get("propValue");
                break;
            }
        }

        if (winningIndex < 0) {
            // Nothing stored anywhere -- the model's own default wins, nothing to override.
            PropertyExplanation explanation = new PropertyExplanation(
                    propertyKey, property.defaultValue(), PropertyExplanation.ScopeRef.DEFAULT, List.of());
            cache.put(cacheKey, explanation);
            return explanation;
        }

        List<PropertyExplanation.OverriddenScope> overrode = new ArrayList<>();
        for (int i = winningIndex + 1; i < scopes.size(); i++) {
            String scopeId = resolvedScopeIds.get(i);
            if (scopeId == null) {
                continue;
            }
            Optional<ConceptRecord> row = findRow(scopes.get(i).name(), scopeId, propertyKey, context);
            if (row.isPresent()) {
                Object raw = row.get().data().get("propValue");
                overrode.add(new PropertyExplanation.OverriddenScope(
                        scopes.get(i).name(), scopeId, coerce(property.type(), raw)));
            }
        }
        overrode.add(new PropertyExplanation.OverriddenScope("default", null, property.defaultValue()));

        String winningScopeType = scopes.get(winningIndex).name();
        String winningScopeId = resolvedScopeIds.get(winningIndex);
        Object value = coerce(property.type(), winningRawValue);
        PropertyExplanation explanation = new PropertyExplanation(propertyKey, value,
                new PropertyExplanation.ScopeRef(winningScopeType, winningScopeId), overrode);
        cache.put(cacheKey, explanation);
        return explanation;
    }

    @Override
    public void set(String scopeType, String scopeId, String propertyKey, Object propertyValue, ExecutionContext context) {
        CompiledProperty property = declaredProperty(propertyKey);
        if (!property.settableAt().contains(scopeType)) {
            throw new PropertyNotSettableAtScopeException(propertyKey, scopeType, property.settableAt());
        }

        Optional<ConceptRecord> existing = findRow(scopeType, scopeId, propertyKey, context);
        Object oldRawValue = existing.map(record -> record.data().get("propValue")).orElse(null);
        String id = existing.map(ConceptRecord::id).orElseGet(() -> UUID.randomUUID().toString());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("scopeType", scopeType);
        data.put("scopeId", scopeId);
        data.put("propKey", propertyKey);
        data.put("propValue", propertyValue == null ? null : String.valueOf(propertyValue));

        conceptGateway.save(new ConceptWriteRequest(CONCEPT_NAME, id, context.tenantId(), data), context);
        // Bump BEFORE clearing: any explain() racing this set() either snapshots the OLD version (its
        // eventual cache.put is keyed by a version nobody will look up again once the bump below is
        // visible) or the NEW one (a genuine post-write read) -- never a version that could serve a
        // stale value under the NEW key. See PropertyResolverCacheConcurrencyTest for the proof.
        version.incrementAndGet();
        cache.clear();
        audit(scopeType, scopeId, propertyKey, existing.isPresent(), oldRawValue, propertyValue, context);
    }

    private void audit(String scopeType, String scopeId, String propertyKey, boolean hadPriorRow,
                        Object oldRawValue, Object newValue, ExecutionContext context) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("scopeType", scopeType);
        meta.put("scopeId", scopeId);
        meta.put("propKey", propertyKey);
        meta.put("oldValuePresent", String.valueOf(hadPriorRow));
        meta.put("oldValue", oldRawValue == null ? "" : String.valueOf(oldRawValue));
        meta.put("newValueNull", String.valueOf(newValue == null));
        meta.put("newValue", newValue == null ? "" : String.valueOf(newValue));
        auditLogStore.append(AuditRecord.create(
                context.tenantId(), context.actorId(), context.roles(),
                AUDIT_ACTION_WRITE, AUDIT_RESOURCE_TYPE, scopeType + ":" + scopeId + ":" + propertyKey,
                "success", null, context.tags(), meta));
    }

    private CompiledProperty declaredProperty(String propertyKey) {
        for (CompiledProperty property : compiledModel.getProperties()) {
            if (property.name().equals(propertyKey)) {
                return property;
            }
        }
        throw new PropertyNotDeclaredException(propertyKey);
    }

    private Optional<ConceptRecord> findRow(String scopeType, String scopeId, String propertyKey, ExecutionContext context) {
        ConceptQuery query = new ConceptQuery(List.of(
                ConceptQuery.Filter.eq("scopeType", scopeType),
                ConceptQuery.Filter.eq("scopeId", scopeId),
                ConceptQuery.Filter.eq("propKey", propertyKey)
        ), List.of(), 0, 1);
        ConceptPage page = conceptGateway.query(new ConceptQueryRequest(CONCEPT_NAME, context.tenantId(), query), context);
        return page.items().stream().findFirst();
    }

    /**
     * {@code from} grammar is fixed at compile time by {@code PropertyValidation.FROM_GRAMMAR}:
     * blank/absent (the implicit root, always {@code $ctx.tenantId}), {@code "$ctx.tenantId"},
     * {@code "$user.id"}, or {@code "$user.<tagName>"}. Returns {@code null} when the context cannot
     * supply the scope (vector 12) -- the caller skips it, this method never throws.
     */
    private String resolveScopeId(CompiledPropertyScope scope, ExecutionContext context) {
        String from = scope.from();
        if (from == null || "$ctx.tenantId".equals(from)) {
            return context.tenantId();
        }
        if ("$user.id".equals(from)) {
            return context.actorId();
        }
        String tagName = from.substring("$user.".length());
        return context.tags().get(tagName);
    }

    private static Object coerce(String type, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String raw = String.valueOf(rawValue);
        return switch (type) {
            case "int" -> Integer.valueOf(raw);
            case "boolean" -> Boolean.valueOf(raw);
            default -> raw; // string, enum, date -- stored and returned verbatim
        };
    }

    /** {@code resolvedScopeIds} is already an immutable snapshot (List.copyOf at the explain() call site). */
    private record CacheKey(String tenantId, String propertyKey, List<String> resolvedScopeIds, long version) {
    }
}
