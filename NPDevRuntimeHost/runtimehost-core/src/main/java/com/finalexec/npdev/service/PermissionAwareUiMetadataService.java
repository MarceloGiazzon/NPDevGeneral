package com.finalexec.npdev.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PermissionAwareUiMetadataService {

    private static final String POLICY_CLASSPATH = "npdev/security/dev.ui-metadata-policy.json";

    private final RuntimeMetadataService runtimeMetadataService;
    private final ObjectMapper objectMapper;
    private final PermissionEvaluator permissionEvaluator;
    private final BetaSecurityRoleEvaluator roleEvaluator;

    public PermissionAwareUiMetadataService(
            RuntimeMetadataService runtimeMetadataService,
            ObjectMapper objectMapper,
            PermissionEvaluator permissionEvaluator,
            BetaSecurityRoleEvaluator roleEvaluator
    ) {
        this.runtimeMetadataService = runtimeMetadataService;
        this.objectMapper = objectMapper;
        this.permissionEvaluator = permissionEvaluator;
        this.roleEvaluator = roleEvaluator;
    }

    public Map<String, Object> actions(String conceptName, String ownerName, ExecutionContext context) {
        Map<String, Object> response = new LinkedHashMap<>(runtimeMetadataService.actions(conceptName, ownerName, localeOf(context)));
        ActionEvaluation evaluation = evaluateActions(extractItems(response), context);
        response.put("permissionAware", true);
        response.put("policyPath", POLICY_CLASSPATH);
        response.put("policyVersion", policy().policyVersion());
        response.put("actor", roleEvaluator.actorSummary(context));
        response.put("filteredCount", evaluation.visibleItems().size());
        response.put("items", evaluation.visibleItems());
        response.put("suppressedItems", evaluation.hiddenItems());
        response.put("disabledCount", evaluation.disabledCount());
        response.put("hiddenCount", evaluation.hiddenItems().size());
        response.put("grantedPermissionHints", distinctPermissionHints(evaluation.visibleItems(), true));
        response.put("deniedPermissionHints", distinctPermissionHints(allItems(evaluation), false));
        response.put("denialReasons", denialReasons(allItems(evaluation)));
        return response;
    }

    public Map<String, Object> fields(String conceptName, String fieldPath, ExecutionContext context) {
        Map<String, Object> response = new LinkedHashMap<>(runtimeMetadataService.fields(conceptName, fieldPath, localeOf(context)));
        FieldEvaluation evaluation = evaluateFields(extractItems(response), context);
        response.put("permissionAware", true);
        response.put("policyPath", POLICY_CLASSPATH);
        response.put("policyVersion", policy().policyVersion());
        response.put("actor", roleEvaluator.actorSummary(context));
        response.put("filteredCount", evaluation.visibleItems().size());
        response.put("items", evaluation.visibleItems());
        response.put("suppressedItems", evaluation.hiddenItems());
        response.put("readonlyCount", evaluation.readonlyCount());
        response.put("hiddenCount", evaluation.hiddenItems().size());
        response.put("denialReasons", denialReasons(allItems(evaluation)));
        return response;
    }

    public Map<String, Object> previewSupport(String conceptName, ExecutionContext context) {
        // R5.6: the locale-aware overload -- unlike fields()/actions() below, previewSupport's own
        // nested "previewSupport" object (listColumns/summaryFields/referencePickers, built INSIDE
        // RuntimeMetadataService from raw layout/action items) is never re-touched by this method, so
        // resolving here via localizeLabels alone would miss it. Passing the locale down instead means
        // RuntimeMetadataService resolves every label BEFORE building those derived views, so they
        // inherit already-correct text with no separate localization pass needed for them.
        Map<String, Object> response = new LinkedHashMap<>(runtimeMetadataService.previewSupport(conceptName, localeOf(context)));
        Map<String, Object> fieldResponse = fields(conceptName, null, context);
        Map<String, Object> actionResponse = actions(conceptName, null, context);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visibleFields = (List<Map<String, Object>>) fieldResponse.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hiddenFields = (List<Map<String, Object>>) fieldResponse.get("suppressedItems");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visibleActions = (List<Map<String, Object>>) actionResponse.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hiddenActions = (List<Map<String, Object>>) actionResponse.get("suppressedItems");

        response.put("permissionAware", true);
        response.put("policyPath", POLICY_CLASSPATH);
        response.put("policyVersion", policy().policyVersion());
        response.put("actor", roleEvaluator.actorSummary(context));
        response.put("fields", visibleFields);
        response.put("suppressedFields", hiddenFields);
        response.put("actions", visibleActions);
        response.put("suppressedActions", hiddenActions);
        response.put("permissionSummary", Map.of(
                "readonlyFieldCount", fieldResponse.get("readonlyCount"),
                "hiddenFieldCount", fieldResponse.get("hiddenCount"),
                "disabledActionCount", actionResponse.get("disabledCount"),
                "hiddenActionCount", actionResponse.get("hiddenCount")
        ));

        Map<String, Object> previewSupport = castMap(response.get("previewSupport"));
        previewSupport.put("actionLabels", actionLabels(visibleActions));
        previewSupport.put("permissionAware", true);
        response.put("previewSupport", previewSupport);
        return response;
    }

    /**
     * F2.2 (docs/FRONTEND_STRATEGY_PLAN.md &sect;2.3): the single-call UI contract for a screen --
     * composes the SAME {@link #fields}/{@link #actions} this class already exposes individually (the
     * anti-drift property the acceptance test pins: {@code bundle.fields == fields(...).items} for the
     * same caller), plus the catalogs that have no per-actor filter anywhere in the platform yet
     * (layout/enums/references/transitions/validation/invocations) passed through unfiltered from
     * {@link RuntimeMetadataService}. Inventing a NEW permission filter for those six would be a much
     * larger, uncosted addition than "compose the existing filters" asks for -- there is nothing
     * existing to compose for them.
     *
     * <p>{@code concept} scope takes priority when both {@code conceptName} and {@code panelName} are
     * given (the sketch's own {@code ?concept=X|?panel=Y} reads as mutually exclusive). Unscoped
     * (both blank) returns every item of every catalog.
     *
     * <p>{@code modelHash} is {@link RuntimeMetadataService#schemaFingerprint()} verbatim -- the same
     * value {@code SchemaLifecycleExecutor} stamps, per the plan's explicit "do not mint a second
     * hash" instruction. It covers table/column/type/required/unique shape only (see that method's
     * javadoc); F4 drift-detection is therefore precise for a field rename (the scenario it exists to
     * catch) but under-fires for a panel/action/permission/flow/lifecycle-only edit. Accepted boundary.
     */
    public Map<String, Object> bundle(String conceptName, String panelName, ExecutionContext context) {
        String concept = stringValue(conceptName);
        String panel = stringValue(panelName);

        Map<String, Object> fieldResponse = fields(concept, null, context);
        Map<String, Object> actionResponse = actions(concept, null, context);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "npdev-ui-contract.v1");
        response.put("modelHash", runtimeMetadataService.schemaFingerprint());
        response.put("generatedAt", Instant.now().toString());
        response.put("namespace", stringValue(runtimeMetadataService.overview().get("namespace")));
        response.put("permissionAware", true);

        Map<String, Object> scope = new LinkedHashMap<>();
        if (!concept.isBlank()) {
            scope.put("concept", concept);
        }
        if (!panel.isBlank()) {
            scope.put("panel", panel);
        }
        response.put("scope", scope);

        response.put("concept", concept.isBlank() ? null : castMap(runtimeMetadataService.concept(concept, localeOf(context)).get("concept")));
        response.put("fields", fieldResponse.get("items"));
        response.put("layout", rawCatalogItems("layout", concept, context));
        response.put("enums", rawCatalogItems("enums", concept, context));
        response.put("references", rawCatalogItems("references", concept, context));
        response.put("actions", actionResponse.get("items"));
        response.put("transitions", rawCatalogItems("transitions", concept, context));
        response.put("validation", rawCatalogItems("validationHints", concept, context));
        response.put("invocations", invocationItems(concept, panel, context));

        response.put("apiBase", "/api/v1");
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("scheme", "bearer");
        auth.put("tenantHeader", "X-Tenant-Id");
        response.put("auth", auth);
        return response;
    }

    private List<Map<String, Object>> rawCatalogItems(String catalogName, String concept, ExecutionContext context) {
        return extractItems(runtimeMetadataService.catalog(catalogName, concept, null, null, localeOf(context)));
    }

    /** R5.6/EDIT-13: the requested locale tag off {@code context} ({@link ExecutionContext#locale()}),
     * or null when there is none / no context at all -- the "null means unresolved, byte-identical to
     * before" contract every {@code RuntimeMetadataService} locale-aware overload relies on.
     * Resolution itself happens once, centrally, in {@code RuntimeMetadataService#resolveLabels}
     * (which also strips the raw {@code labelLocales}/{@code shortLabelLocales} map from the item),
     * so this class only ever hands down the plain locale tag, never re-implements resolution. */
    private static String localeOf(ExecutionContext context) {
        return context == null ? null : context.locale();
    }

    /** Concept scope filters the invocations catalog by its "concept" property, same as every other
     * catalog here. Panel scope has no such property to reuse generically -- panelAction/panelRowAdd/
     * panelRowDelete entries key on "panel" instead -- so it is filtered by hand here rather than
     * stretching {@code RuntimeMetadataService}'s single-key concept filter to cover a second key. */
    private List<Map<String, Object>> invocationItems(String concept, String panel, ExecutionContext context) {
        if (!concept.isBlank()) {
            return rawCatalogItems("invocations", concept, context);
        }
        List<Map<String, Object>> all = rawCatalogItems("invocations", "", context);
        if (panel.isBlank()) {
            return all;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : all) {
            if (panel.equalsIgnoreCase(stringValue(item.get("panel")))) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private ActionEvaluation evaluateActions(List<Map<String, Object>> items, ExecutionContext context) {
        List<Map<String, Object>> visible = new ArrayList<>();
        List<Map<String, Object>> hidden = new ArrayList<>();
        int disabledCount = 0;
        for (Map<String, Object> item : items) {
            Map<String, Object> enriched = new LinkedHashMap<>(item);
            ActionPolicy actionPolicy = findActionPolicy(item);
            PermissionDecision decision = evaluatePermission(item, context);
            String permissionHint = stringValue(item.get("permissionHint"));
            boolean allowed = decision.allowed() || permissionHint.isBlank();
            ActionUiState uiState = allowed
                    ? ActionUiState.ENABLED
                    : ActionUiState.fromDenyMode(actionPolicy == null ? "" : actionPolicy.denyMode());
            enriched.put("requiredPermission", permissionHint);
            enriched.put("available", allowed);
            enriched.put("visible", uiState != ActionUiState.HIDDEN);
            enriched.put("uiState", uiState.jsonValue());
            enriched.put("permissionDecisionCode", decision.code());
            if (!allowed) {
                Map<String, Object> denial = new LinkedHashMap<>();
                denial.put("code", decision.code().isBlank() ? "permission_denied" : decision.code());
                denial.put("message", denialMessage(actionPolicy, decision));
                denial.put("requiredPermission", permissionHint);
                enriched.put("denial", denial);
            }
            if (uiState == ActionUiState.HIDDEN) {
                hidden.add(enriched);
            } else {
                if (!allowed) {
                    disabledCount++;
                }
                visible.add(enriched);
            }
        }
        return new ActionEvaluation(List.copyOf(visible), List.copyOf(hidden), disabledCount);
    }

    private FieldEvaluation evaluateFields(List<Map<String, Object>> items, ExecutionContext context) {
        List<Map<String, Object>> visible = new ArrayList<>();
        List<Map<String, Object>> hidden = new ArrayList<>();
        int readonlyCount = 0;
        for (Map<String, Object> item : items) {
            Map<String, Object> enriched = new LinkedHashMap<>(item);
            FieldAccessState state = resolveFieldState(item, context);
            enriched.put("permissionState", state.jsonValue());
            enriched.put("visible", state != FieldAccessState.HIDDEN);
            enriched.put("editable", state == FieldAccessState.EDITABLE);
            String message = fieldStateMessage(item, state);
            if (!message.isBlank() && state != FieldAccessState.EDITABLE) {
                Map<String, Object> denial = new LinkedHashMap<>();
                denial.put("code", state == FieldAccessState.READONLY ? "readonly_by_policy" : "hidden_by_policy");
                denial.put("message", message);
                denial.put("fieldPath", stringValue(item.get("fieldPath")));
                enriched.put("denial", denial);
            }
            if (state == FieldAccessState.HIDDEN) {
                hidden.add(enriched);
            } else {
                if (state == FieldAccessState.READONLY) {
                    readonlyCount++;
                }
                visible.add(enriched);
            }
        }
        return new FieldEvaluation(List.copyOf(visible), List.copyOf(hidden), readonlyCount);
    }

    private PermissionDecision evaluatePermission(Map<String, Object> item, ExecutionContext context) {
        String permissionHint = stringValue(item.get("permissionHint"));
        if (permissionHint.isBlank()) {
            return PermissionDecision.allow("no_permission_hint");
        }
        PermissionSubject subject = new PermissionSubject(
                context == null ? "" : context.actorId(),
                context == null ? "" : context.tenantId(),
                normalizedLowerRoles(context),
                List.of()
        );
        PermissionRequirement requirement = new PermissionRequirement(
                permissionHint,
                stringValue(item.get("kind")),
                stringValue(item.get("name"))
        );
        return permissionEvaluator.evaluate(subject, requirement);
    }

    private List<String> normalizedLowerRoles(ExecutionContext context) {
        List<String> roles = new ArrayList<>();
        for (String role : roleEvaluator.normalizedRoles(context)) {
            roles.add(role.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(roles);
    }

    private FieldAccessState resolveFieldState(Map<String, Object> item, ExecutionContext context) {
        FieldPolicy fieldPolicy = findFieldPolicy(item);
        if (fieldPolicy == null || fieldPolicy.statesByRole() == null || fieldPolicy.statesByRole().isEmpty()) {
            return FieldAccessState.EDITABLE;
        }
        Map<String, String> configuredStates = normalizeRoleMap(fieldPolicy.statesByRole());
        for (String role : orderedRoles(context)) {
            String configured = configuredStates.get(role);
            if (configured != null) {
                return FieldAccessState.from(configured);
            }
        }
        if (configuredStates.containsKey("*")) {
            return FieldAccessState.from(configuredStates.get("*"));
        }
        return FieldAccessState.EDITABLE;
    }

    private List<String> orderedRoles(ExecutionContext context) {
        Set<String> roles = new LinkedHashSet<>(roleEvaluator.normalizedRoles(context));
        List<String> ordered = new ArrayList<>();
        for (String preferred : List.of("ADMIN", "OPERATOR", "SUPPORT", "USER", "DEBUG")) {
            if (roles.remove(preferred)) {
                ordered.add(preferred);
            }
        }
        List<String> remaining = new ArrayList<>(roles);
        remaining.sort(Comparator.naturalOrder());
        ordered.addAll(remaining);
        return ordered;
    }

    private Map<String, String> normalizeRoleMap(Map<String, String> source) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = stringValue(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            normalized.put("*".equals(key) ? "*" : key.toUpperCase(Locale.ROOT), stringValue(entry.getValue()));
        }
        return normalized;
    }

    private FieldPolicy findFieldPolicy(Map<String, Object> item) {
        String concept = stringValue(item.get("concept"));
        String fieldPath = stringValue(item.get("fieldPath"));
        for (FieldPolicy policy : policy().fieldPolicies()) {
            if (policy.matches(concept, fieldPath)) {
                return policy;
            }
        }
        return null;
    }

    private ActionPolicy findActionPolicy(Map<String, Object> item) {
        String name = stringValue(item.get("name"));
        String ownerName = stringValue(item.get("ownerName"));
        String kind = stringValue(item.get("kind"));
        for (ActionPolicy policy : policy().actionPolicies()) {
            if (policy.matches(name, ownerName, kind)) {
                return policy;
            }
        }
        return null;
    }

    private String fieldStateMessage(Map<String, Object> item, FieldAccessState state) {
        FieldPolicy fieldPolicy = findFieldPolicy(item);
        if (fieldPolicy == null || state == FieldAccessState.EDITABLE) {
            return "";
        }
        return fieldPolicy.messageFor(state.jsonValue());
    }

    private String denialMessage(ActionPolicy actionPolicy, PermissionDecision decision) {
        if (actionPolicy != null && !actionPolicy.denialMessage().isBlank()) {
            return actionPolicy.denialMessage();
        }
        return decision.message();
    }

    private List<Map<String, Object>> extractItems(Map<String, Object> response) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object raw = response.get("items");
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                items.add(castMap(item));
            }
        }
        return items;
    }

    private List<Map<String, Object>> actionLabels(List<Map<String, Object>> actionItems) {
        List<Map<String, Object>> labels = new ArrayList<>();
        for (Map<String, Object> item : actionItems) {
            String label = stringValue(item.get("label"));
            if (label.isBlank()) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", stringValue(item.get("name")));
            summary.put("label", label);
            summary.put("kind", stringValue(item.get("kind")));
            summary.put("uiState", stringValue(item.get("uiState")));
            summary.put("requiredPermission", stringValue(item.get("requiredPermission")));
            summary.put("permissionHint", stringValue(item.get("permissionHint")));
            labels.add(summary);
        }
        return labels;
    }

    private List<String> denialReasons(List<Map<String, Object>> items) {
        Set<String> reasons = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> denial = castMap(item.get("denial"));
            String message = stringValue(denial.get("message"));
            if (!message.isBlank()) {
                reasons.add(message);
            }
        }
        return new ArrayList<>(reasons);
    }

    private List<String> distinctPermissionHints(List<Map<String, Object>> items, boolean onlyAllowed) {
        Set<String> hints = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            boolean available = Boolean.TRUE.equals(item.get("available"));
            if (onlyAllowed != available) {
                continue;
            }
            String hint = stringValue(item.get("requiredPermission"));
            if (!hint.isBlank()) {
                hints.add(hint);
            }
        }
        return new ArrayList<>(hints);
    }

    private List<Map<String, Object>> allItems(ActionEvaluation evaluation) {
        List<Map<String, Object>> items = new ArrayList<>(evaluation.visibleItems());
        items.addAll(evaluation.hiddenItems());
        return items;
    }

    private List<Map<String, Object>> allItems(FieldEvaluation evaluation) {
        List<Map<String, Object>> items = new ArrayList<>(evaluation.visibleItems());
        items.addAll(evaluation.hiddenItems());
        return items;
    }

    /**
     * The UI metadata policy, or the empty policy when no app has supplied one.
     *
     * <p><b>Absence is not an error, and it used to be.</b> The runtime-host template shipped its own
     * copy of this resource at the same classpath path the generator writes the app's real one to,
     * and the generated app's build lists {@code src/main/resources} before
     * {@code npdev-generated/src/main/resources} under {@code DuplicatesStrategy.EXCLUDE} -- so the
     * TEMPLATE's copy won, in every generated app, and the app's own policy was discarded. The two
     * files did not even have the same shape ({@code items} vs {@code fieldPolicies}/
     * {@code actionPolicies}), so the app's policy was not merely overridden, it was unreadable.
     * Harmless only for as long as both were empty (REG-142, second instance).
     *
     * <p>The template's copy is gone. This method no longer throws on absence, because a boot-time
     * failure was the only thing keeping it there: an app that declares no policy restricts nothing,
     * which is exactly what an empty policy means and exactly what every app got anyway.
     */
    private UiMetadataPolicy policy() {
        ClassPathResource resource = new ClassPathResource(POLICY_CLASSPATH);
        if (!resource.exists()) {
            return UiMetadataPolicy.empty();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            UiMetadataPolicy loaded = objectMapper.readValue(inputStream, new TypeReference<UiMetadataPolicy>() { });
            return loaded == null ? UiMetadataPolicy.empty() : loaded.normalized();
        } catch (Exception exception) {
            // A malformed policy is still an error: it means an app TRIED to say something and this
            // service could not read it. Only absence is benign.
            throw new IllegalStateException("Failed to load UI metadata policy resource: " + POLICY_CLASSPATH, exception);
        }
    }

    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ActionEvaluation(
            List<Map<String, Object>> visibleItems,
            List<Map<String, Object>> hiddenItems,
            int disabledCount
    ) { }

    private record FieldEvaluation(
            List<Map<String, Object>> visibleItems,
            List<Map<String, Object>> hiddenItems,
            int readonlyCount
    ) { }

    private enum ActionUiState {
        ENABLED("enabled"),
        DISABLED("disabled"),
        HIDDEN("hidden");

        private final String jsonValue;

        ActionUiState(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        static ActionUiState fromDenyMode(String denyMode) {
            return "hidden".equalsIgnoreCase(denyMode) ? HIDDEN : DISABLED;
        }

        String jsonValue() {
            return jsonValue;
        }
    }

    private enum FieldAccessState {
        EDITABLE("editable"),
        READONLY("readonly"),
        HIDDEN("hidden");

        private final String jsonValue;

        FieldAccessState(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        static FieldAccessState from(String value) {
            if ("hidden".equalsIgnoreCase(value)) {
                return HIDDEN;
            }
            if ("readonly".equalsIgnoreCase(value)) {
                return READONLY;
            }
            return EDITABLE;
        }

        String jsonValue() {
            return jsonValue;
        }
    }

    private record UiMetadataPolicy(
            String policyVersion,
            List<FieldPolicy> fieldPolicies,
            List<ActionPolicy> actionPolicies
    ) {
        static UiMetadataPolicy empty() {
            return new UiMetadataPolicy("1.0.0", List.of(), List.of());
        }

        UiMetadataPolicy normalized() {
            return new UiMetadataPolicy(
                    policyVersion == null || policyVersion.isBlank() ? "1.0.0" : policyVersion.trim(),
                    fieldPolicies == null ? List.of() : List.copyOf(fieldPolicies),
                    actionPolicies == null ? List.of() : List.copyOf(actionPolicies)
            );
        }
    }

    private record FieldPolicy(
            String concept,
            String fieldPath,
            Map<String, String> statesByRole,
            Map<String, String> messagesByState
    ) {
        boolean matches(String actualConcept, String actualFieldPath) {
            return matchesValue(concept, actualConcept) && matchesValue(fieldPath, actualFieldPath);
        }

        String messageFor(String state) {
            if (messagesByState == null || messagesByState.isEmpty()) {
                return "";
            }
            String value = messagesByState.get(state);
            return value == null ? "" : value.trim();
        }
    }

    private record ActionPolicy(
            String name,
            String ownerName,
            String kind,
            String denyMode,
            String denialMessage
    ) {
        boolean matches(String actualName, String actualOwnerName, String actualKind) {
            return matchesValue(name, actualName)
                    && matchesValue(ownerName, actualOwnerName)
                    && matchesValue(kind, actualKind);
        }
    }

    private static boolean matchesValue(String expected, String actual) {
        String left = expected == null ? "" : expected.trim();
        if (left.isBlank() || "*".equals(left)) {
            return true;
        }
        String right = actual == null ? "" : actual.trim();
        return left.equalsIgnoreCase(right);
    }
}
