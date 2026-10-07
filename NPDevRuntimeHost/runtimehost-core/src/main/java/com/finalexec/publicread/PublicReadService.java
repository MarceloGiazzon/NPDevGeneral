package com.finalexec.publicread;

import com.finalexec.filestore.TenantFileReader;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPublicRead;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryPredicateCompiler;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * P6 (Pigmentampas public gallery, G4): the anonymous, read-only data surface behind
 * {@code /api/public/**} -- driven ONLY by each concept's {@code access.public} grant
 * ({@link CompiledPublicRead}). A concept without one does not exist here (every miss is the same
 * "not found", so the surface never confirms that a private concept or row exists).
 *
 * <p>What it guarantees, independent of how the gateway itself would treat the principal:
 * <ul>
 *   <li>rows: the grant's {@code where} is pushed into the store query (AND-ed with any caller
 *       filter), so totals and pages only ever count public rows;</li>
 *   <li>fields: every row is projected onto {@code id} + the grant's allow-list; a {@code file}
 *       field becomes {@code {url}} pointing at {@link #image}, never the raw store handle;</li>
 *   <li>caller filters/sorts are equality-only and only on allow-listed fields; page size is capped
 *       at {@link #MAX_PAGE};</li>
 *   <li>files: only allow-listed {@code file} fields, only images, only under the public tenant
 *       ({@link TenantFileReader}'s tenant check);</li>
 *   <li>aggregates: the root must be public and pass its {@code where}; a child collection is served
 *       only when its concept declares {@code access.public} (either scope), projected the same way.</li>
 * </ul>
 * Reads go through the {@link ConceptGateway} (tenant isolation, audit) as a dedicated
 * {@code public:anonymous} principal; the gateway's own row scope still applies on top, so the
 * grant can only narrow what a broad reader would see, never widen it past a concept's access.read.
 */
public final class PublicReadService {

    public static final int DEFAULT_PAGE = 24;
    public static final int MAX_PAGE = 100;
    public static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    public static final String ACTOR_ID = "public:anonymous";

    /** Thrown for anything that must read as a uniform 404 (unknown/private concept, row, field). */
    public static final class NotPublicException extends RuntimeException {
        public NotPublicException(String message) {
            super(message);
        }
    }

    public record Image(byte[] bytes, String contentType) {
    }

    private final Supplier<CompiledModel> model;
    private final Supplier<ConceptGateway> gateway;
    private final TenantFileReader fileReader;
    private final String tenantId;
    private final String basePath;
    private final java.util.function.Predicate<String> tenantActive;

    public PublicReadService(Supplier<CompiledModel> model, Supplier<ConceptGateway> gateway,
                             TenantFileReader fileReader, String tenantId, String basePath) {
        this(model, gateway, fileReader, tenantId, basePath, tenant -> true);
    }

    /** @param tenantActive the same disabled-tenant switch TenantStatusFilter applies to signed-in callers */
    public PublicReadService(Supplier<CompiledModel> model, Supplier<ConceptGateway> gateway,
                             TenantFileReader fileReader, String tenantId, String basePath,
                             java.util.function.Predicate<String> tenantActive) {
        this.tenantActive = tenantActive == null ? tenant -> true : tenantActive;
        this.model = model;
        this.gateway = gateway;
        this.fileReader = fileReader;
        this.tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        this.basePath = basePath == null ? "/api/public" : basePath;
    }

    /** The principal every public read runs as: no caller identity, tagged so audit tells it apart. */
    ExecutionContext context() {
        return new ExecutionContext(tenantId, ACTOR_ID, Map.of("trigger", "public"), Set.of("ADMIN"));
    }

    /** The public concepts and their served fields -- what a public client may ask for. */
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompiledConcept concept : requireModel().getConcepts()) {
            CompiledPublicRead grant = grantOf(concept);
            if (grant != null && grant.directlyReadable()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("concept", concept.getName());
                entry.put("fields", servedFields(concept, grant));
                out.add(entry);
            }
        }
        return out;
    }

    public Map<String, Object> page(String conceptName, Map<String, String[]> params) {
        CompiledConcept concept = directConcept(conceptName);
        CompiledPublicRead grant = grantOf(concept);
        Set<String> served = servedFieldSet(concept, grant);
        int limit = clamp(intParam(params, "limit", DEFAULT_PAGE), 1, MAX_PAGE);
        int offset = Math.max(0, intParam(params, "offset", 0));

        List<ConceptQuery.Filter> callerFilters = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || Set.of("limit", "offset", "sort", "direction").contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String field = canonicalField(served, key);
            String[] values = entry.getValue();
            if (field == null) {
                throw new IllegalArgumentException("filter on a non-public field: " + key);
            }
            if (values != null && values.length > 0 && values[0] != null && !values[0].isBlank()) {
                callerFilters.add(ConceptQuery.Filter.eq(field, values[0]));
            }
        }
        List<ConceptQuery.Sort> sorts = new ArrayList<>();
        String sort = first(params, "sort");
        if (sort != null && !sort.isBlank()) {
            String field = canonicalField(served, sort);
            if (field == null) {
                throw new IllegalArgumentException("sort on a non-public field: " + sort);
            }
            String direction = first(params, "direction");
            sorts.add(new ConceptQuery.Sort(field, "desc".equalsIgnoreCase(direction) || "descending".equalsIgnoreCase(direction)));
        }

        ConceptPage page = query(concept, grant, callerFilters, sorts, offset, limit);
        List<Map<String, Object>> items = new ArrayList<>(page.items().size());
        for (ConceptRecord record : page.items()) {
            items.add(project(concept, grant, record));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("total", page.total());
        response.put("hasMore", page.hasMore());
        response.put("offset", offset);
        response.put("limit", limit);
        return response;
    }

    public Map<String, Object> get(String conceptName, String id) {
        CompiledConcept concept = directConcept(conceptName);
        CompiledPublicRead grant = grantOf(concept);
        return project(concept, grant, findPublic(concept, grant, id));
    }

    public Image image(String conceptName, String id, String fieldName) {
        CompiledConcept concept = directConcept(conceptName);
        CompiledPublicRead grant = grantOf(concept);
        String field = canonicalField(new LinkedHashSet<>(grant.fields()), fieldName);
        CompiledField compiled = field == null ? null : fieldOf(concept, field);
        if (compiled == null || compiled.getFile() == null) {
            throw new NotPublicException("not found");
        }
        Object value = findPublic(concept, grant, id).data().get(field);
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new NotPublicException("not found");
        }
        try {
            TenantFileReader.ImageInput image = fileReader.readImage(value, tenantId, MAX_IMAGE_BYTES);
            return new Image(image.bytes(), image.mimeType());
        } catch (IllegalArgumentException notServable) {
            throw new NotPublicException("not found");
        }
    }

    public Map<String, Object> aggregate(String aggregateName, String rootId) {
        CompiledAggregate aggregate = requireModel().getAggregates().stream()
                .filter(candidate -> candidate.name() != null && candidate.name().equalsIgnoreCase(aggregateName))
                .findFirst()
                .orElseThrow(() -> new NotPublicException("not found"));
        CompiledConcept root = conceptNamed(aggregate.root()).orElseThrow(() -> new NotPublicException("not found"));
        CompiledPublicRead rootGrant = grantOf(root);
        if (rootGrant == null) {
            throw new NotPublicException("not found");
        }
        ConceptRecord rootRecord = findPublic(root, rootGrant, rootId);
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("aggregate", aggregate.name());
        tree.putAll(project(root, rootGrant, rootRecord));
        for (CompiledAggregateCollection collection : aggregate.collections()) {
            putCollection(tree, collection, rootRecord.id());
        }
        return tree;
    }

    private void putCollection(Map<String, Object> parent, CompiledAggregateCollection collection, String parentId) {
        CompiledConcept child = conceptNamed(collection.concept()).orElse(null);
        CompiledPublicRead grant = child == null ? null : grantOf(child);
        if (grant == null) {
            return; // a private child collection is simply absent from the public tree
        }
        List<ConceptQuery.Sort> sorts = List.of();
        if (collection.orderBy() != null && !collection.orderBy().isBlank()) {
            try {
                sorts = ConceptQueryPredicateCompiler.compileOrderBy(List.of(collection.orderBy()));
            } catch (RuntimeException ignored) {
                sorts = List.of();
            }
        }
        ConceptPage page = query(child, grant, List.of(ConceptQuery.Filter.eq(collection.childField(), parentId)),
                sorts, 0, ConceptQuery.MAX_LIMIT);
        List<Map<String, Object>> rows = new ArrayList<>(page.items().size());
        for (ConceptRecord record : page.items()) {
            Map<String, Object> row = project(child, grant, record);
            for (CompiledAggregateCollection nested : collection.collections()) {
                putCollection(row, nested, record.id());
            }
            rows.add(row);
        }
        parent.put(collection.name(), rows);
    }

    private ConceptRecord findPublic(CompiledConcept concept, CompiledPublicRead grant, String id) {
        if (id == null || id.isBlank()) {
            throw new NotPublicException("not found");
        }
        ConceptPage page = query(concept, grant, List.of(ConceptQuery.Filter.eq("id", id)), List.of(), 0, 1);
        if (page.items().isEmpty()) {
            throw new NotPublicException("not found");
        }
        return page.items().get(0);
    }

    /** The grant's {@code where} AND the extra filters, as one store query (DNF product). */
    private ConceptPage query(CompiledConcept concept, CompiledPublicRead grant, List<ConceptQuery.Filter> extra,
                              List<ConceptQuery.Sort> sorts, int offset, int limit) {
        List<ConceptQuery.Filter> filters = combine(
                grant.where() == null ? List.of() : ConceptQueryPredicateCompiler.compileToConceptQueryFilters(grant.where()),
                extra);
        ConceptGateway conceptGateway = gateway.get();
        if (conceptGateway == null) {
            throw new IllegalStateException("concept gateway not configured");
        }
        ExecutionContext context = context();
        return conceptGateway.query(new ConceptQueryRequest(concept.getName(), context.tenantId(),
                new ConceptQuery(filters, sorts, offset, limit)), context);
    }

    @SuppressWarnings("unchecked")
    static List<ConceptQuery.Filter> combine(List<ConceptQuery.Filter> where, List<ConceptQuery.Filter> extra) {
        if (where.size() == 1 && where.get(0).operator() == ConceptQuery.Operator.OR_GROUPS) {
            if (extra.isEmpty()) {
                return where;
            }
            List<List<ConceptQuery.Filter>> groups = new ArrayList<>();
            for (List<ConceptQuery.Filter> group : (List<List<ConceptQuery.Filter>>) where.get(0).value()) {
                List<ConceptQuery.Filter> combined = new ArrayList<>(group);
                combined.addAll(extra);
                groups.add(combined);
            }
            return List.of(ConceptQuery.Filter.orGroups(groups));
        }
        List<ConceptQuery.Filter> out = new ArrayList<>(where);
        out.addAll(extra);
        return out;
    }

    private Map<String, Object> project(CompiledConcept concept, CompiledPublicRead grant, ConceptRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", record.id());
        for (String name : grant.fields()) {
            CompiledField field = fieldOf(concept, name);
            if (field == null || "id".equalsIgnoreCase(field.getName())) {
                continue;
            }
            Object value = record.data().get(field.getName());
            if (field.getFile() != null) {
                boolean present = value != null && !(value instanceof String text && text.isBlank());
                out.put(field.getName(), present ? Map.of("url", imageUrl(concept, record.id(), field.getName())) : null);
            } else {
                out.put(field.getName(), value);
            }
        }
        return out;
    }

    private String imageUrl(CompiledConcept concept, String id, String field) {
        return basePath + "/concepts/" + encode(concept.getName()) + "/" + encode(id) + "/image/" + encode(field);
    }

    private CompiledConcept directConcept(String name) {
        CompiledConcept concept = conceptNamed(name).orElseThrow(() -> new NotPublicException("not found"));
        CompiledPublicRead grant = grantOf(concept);
        if (grant == null || !grant.directlyReadable()) {
            throw new NotPublicException("not found");
        }
        return concept;
    }

    private Optional<CompiledConcept> conceptNamed(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return requireModel().getConcepts().stream()
                .filter(concept -> concept.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    private static CompiledPublicRead grantOf(CompiledConcept concept) {
        return concept.getAccess() == null ? null : concept.getAccess().getPublicRead();
    }

    private static CompiledField fieldOf(CompiledConcept concept, String name) {
        for (CompiledField field : concept.getFields()) {
            if (field.getName().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static List<String> servedFields(CompiledConcept concept, CompiledPublicRead grant) {
        return new ArrayList<>(servedFieldSet(concept, grant));
    }

    private static Set<String> servedFieldSet(CompiledConcept concept, CompiledPublicRead grant) {
        Set<String> served = new LinkedHashSet<>();
        served.add("id");
        for (String name : grant.fields()) {
            CompiledField field = fieldOf(concept, name);
            if (field != null) {
                served.add(field.getName());
            }
        }
        return served;
    }

    private static String canonicalField(Set<String> served, String requested) {
        for (String name : served) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        return null;
    }

    private CompiledModel requireModel() {
        CompiledModel compiled = model.get();
        if (compiled == null || !tenantActive.test(tenantId)) {
            throw new NotPublicException("not found");
        }
        return compiled;
    }

    private static String first(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static int intParam(Map<String, String[]> params, String name, int fallback) {
        String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
