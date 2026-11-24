package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.RoleAst;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.query.PickerFilterGrammar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * FORM P5-T1: semantic checks for the optional top-level {@code experiences[]} (who lands where, on which
 * device) and {@code screens[]} (the closed set of screen archetypes they navigate to). Both arrays are carried
 * as opaque JSON ({@link ModelAst#getExperiences()}), so this class reads them as {@link JsonNode}s and checks
 * every reference against the model's OWN concepts, roles, queries and flows. Unknown extra properties and the
 * shape of each entry are rejected by the schema ({@code model.schema.json}'s {@code experience}/{@code screen}
 * defs), never here; a few rules (the kind set, the column cap) are repeated because a model built without the
 * schema pass must still be refused.
 *
 * <p><b>A screen never widens access.</b> Nothing is enforced here because nothing in this layer grants access:
 * a screen's {@code where} only narrows the rows row-level access already allows, and an action is a request to
 * start a flow whose own roles/rules still decide. Access stays in {@code roles[]}, {@code access} and flow
 * {@code expose}; the runtime (a later task) hides an action the user's roles may not start.
 *
 * <p>Rules: experience names are non-blank and unique; every role in {@code for} is a declared role (a pack's
 * roles included -- {@link ModelAst#getRoles()} is already the composed list); {@code home} and every
 * {@code navigation} entry name a declared screen; navigation has at most 5 entries on a mobile or both device
 * (a bottom tab bar); screen names are non-blank and unique; {@code kind} is in the closed set; list, detail,
 * status, inbox, board and map screens need a {@code concept} or {@code query} that exists; {@code columns} has
 * at most 7 entries; every field named in {@code columns}/{@code fields}/{@code groupBy} (and a map's
 * lat/lng) exists on the screen's concept; every action's {@code flow} exists and is exposed as api or ui
 * (never internal); every expression string parses with {@link ComputedExpression#parse}; {@code component} is
 * allowed only on, and required by, {@code kind: custom}.
 *
 * <p>A field entry that is not a plain identifier (a dotted read such as {@code ride.fareCents}, or an
 * expression) is treated as an expression and only parse-checked: its type-check needs the runtime scopes
 * ({@code $row}, {@code $form}, {@code $user}) that arrive with the experience emitter.
 */
final class ExperienceValidation {

    private static final Set<String> KINDS = Set.of(
            "list", "detail", "form", "status", "inbox", "board", "map", "dashboard", "workbench", "document",
            "picker", "custom");
    private static final Set<String> KINDS_NEEDING_DATA = Set.of("list", "detail", "status", "inbox", "board", "map");
    private static final Set<String> DEVICES = Set.of("web", "mobile", "both");
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final int MAX_COLUMNS = 7;
    /** P5-T9a: properties that only mean something on a picker screen (the old selectors[] entry's own keys). */
    private static final List<String> PICKER_ONLY_KEYS = List.of("multiSelect", "filters", "returnMapping");
    /** P5-T9b: properties that only mean something on a document screen (the old documents[] entry's own keys). */
    private static final List<String> DOCUMENT_ONLY_KEYS = List.of(
            "pageSize", "marginMm", "metadata", "aggregate", "bands", "logo", "report", "printColumns", "subtotals", "grandTotal");
    /** P5-T9b: screen properties a printable document has no use for (accepting them silently would hide an authoring mistake). */
    private static final List<String> DOCUMENT_INAPPLICABLE_KEYS = List.of(
            "columns", "fields", "widgets", "actions", "params", "where", "pick", "orderBy", "map", "live", "countdown",
            "empty", "component", "multiSelect", "filters", "returnMapping");
    private static final int MAX_MOBILE_NAVIGATION = 5;

    private ExperienceValidation() {
    }

    static void validate(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        List<JsonNode> experiences = modelAst.getExperiences();
        List<JsonNode> screens = modelAst.getScreens();
        if (experiences.isEmpty() && screens.isEmpty()) {
            return;
        }

        Set<String> roleNames = new HashSet<>();
        for (RoleAst role : modelAst.getRoles()) {
            if (hasText(role.name())) {
                roleNames.add(normalize(role.name()));
            }
        }
        Map<String, QueryAst> queriesByLower = new HashMap<>();
        for (QueryAst query : modelAst.getQueries()) {
            if (hasText(query.name())) {
                queriesByLower.put(normalize(query.name()), query);
            }
        }
        Map<String, FlowAst> flowsByLower = new HashMap<>();
        for (FlowAst flow : modelAst.getFlows()) {
            if (hasText(flow.getName())) {
                flowsByLower.put(normalize(flow.getName()), flow);
            }
        }

        // Pass 1: screen names, so experiences and actions can reference a screen declared further down.
        Set<String> screenNames = new HashSet<>();
        for (JsonNode screen : screens) {
            String name = text(screen, "name");
            if (!hasText(name)) {
                errors.add("Screen: name is required -- suggestedFix: add a 'name' (for example \"my-trips\") to every entry in screens[]");
            } else if (!screenNames.add(normalize(name))) {
                errors.add("Screen " + name + ": duplicate screen name -- suggestedFix: give each entry in screens[] a distinct name");
            }
        }

        Map<String, JsonNode> screensByLower = new HashMap<>();
        for (JsonNode screen : screens) {
            String screenName = text(screen, "name");
            if (hasText(screenName)) {
                screensByLower.putIfAbsent(normalize(screenName), screen);
            }
        }
        for (JsonNode screen : screens) {
            validateScreen(screen, screenNames, screensByLower, entitiesByLower, queriesByLower, flowsByLower, errors);
        }

        java.util.Map<String, String> adminScreens = new java.util.HashMap<>();
        for (JsonNode screen : screens) {
            if ((screen.has("admin") || screen.has("panel")) && hasText(text(screen, "name"))) {
                adminScreens.put(normalize(text(screen, "name")), screen.has("admin")
                        ? "an 'admin' block (a generated back-office page)" : "a 'panel' block (a hand-written panel)");
            }
        }
        Set<String> experienceNames = new HashSet<>();
        for (JsonNode experience : experiences) {
            validateExperience(experience, experienceNames, roleNames, screenNames, errors);
            refuseAdminTargets(experience, adminScreens, errors);
        }
    }

    /**
     * D-1: a screen with an {@code admin} block is the generated back office of a concept, and a screen with a {@code panel}
     * block is a hand-written panel served at its own route; neither is a screen a role's experience can land on or
     * navigate to (the experience client never serves it).
     */
    private static void refuseAdminTargets(JsonNode experience, java.util.Map<String, String> adminScreens, List<String> errors) {
        if (adminScreens.isEmpty()) {
            return;
        }
        String here = "Experience " + (hasText(text(experience, "name")) ? text(experience, "name") : "<unnamed>");
        List<String> targets = new java.util.ArrayList<>();
        targets.add(text(experience, "home"));
        JsonNode navigation = experience.get("navigation");
        if (navigation != null && navigation.isArray()) {
            for (JsonNode entry : navigation) {
                targets.add(text(entry, "screen"));
            }
        }
        for (String target : targets) {
            if (hasText(target) && adminScreens.containsKey(normalize(target))) {
                errors.add(here + ": screen '" + target + "' carries " + adminScreens.get(normalize(target)) + " and cannot be "
                        + "an experience's home or navigation target -- suggestedFix: add a separate screen without 'admin' "
                        + "for the role to use, or remove it from the experience");
            }
        }
    }

    // ------------------------------------------------------------------ experiences

    private static void validateExperience(
            JsonNode experience,
            Set<String> experienceNames,
            Set<String> roleNames,
            Set<String> screenNames,
            List<String> errors
    ) {
        String name = text(experience, "name");
        String here = "Experience " + (hasText(name) ? name : "<unnamed>");
        if (!hasText(name)) {
            errors.add("Experience: name is required -- suggestedFix: add a 'name' (the URL segment, for example \"rider\") to every entry in experiences[]");
        } else if (!experienceNames.add(normalize(name))) {
            errors.add(here + ": duplicate experience name -- suggestedFix: give each entry in experiences[] a distinct name");
        }

        JsonNode roles = experience.get("for");
        if (roles == null || !roles.isArray() || roles.isEmpty()) {
            errors.add(here + ": 'for' must list at least one role -- suggestedFix: set \"for\" to the role names this experience serves, for example [\"Rider\"]");
        } else {
            for (JsonNode role : roles) {
                String roleName = role.isTextual() ? role.asText() : null;
                if (!hasText(roleName) || !roleNames.contains(normalize(roleName))) {
                    errors.add(here + ": role '" + (roleName == null ? role.toString() : roleName)
                            + "' in 'for' is not declared in roles[] -- suggestedFix: declare the role in roles[] "
                            + "(or in a pack the model imports), or fix the role name in 'for'");
                }
            }
        }

        String device = text(experience, "device");
        if (device != null && !DEVICES.contains(device)) {
            errors.add(here + ": device '" + device + "' must be web, mobile or both -- suggestedFix: set device to \"web\", \"mobile\" or \"both\", or omit it for \"both\"");
        }

        String home = text(experience, "home");
        if (!hasText(home)) {
            errors.add(here + ": home is required -- suggestedFix: set \"home\" to the name of the screen shown first");
        } else if (!screenNames.contains(normalize(home))) {
            errors.add(here + ": home screen '" + home + "' is not declared in screens[] -- suggestedFix: "
                    + "add a screen named '" + home + "' to screens[], or point 'home' at an existing screen");
        }

        JsonNode navigation = experience.get("navigation");
        if (navigation != null && navigation.isArray()) {
            boolean tabBar = device == null || "mobile".equals(device) || "both".equals(device);
            if (tabBar && navigation.size() > MAX_MOBILE_NAVIGATION) {
                errors.add(here + ": navigation has " + navigation.size() + " entries but a "
                        + (device == null ? "both" : device) + " experience shows at most " + MAX_MOBILE_NAVIGATION
                        + " (a bottom tab bar) -- suggestedFix: keep the " + MAX_MOBILE_NAVIGATION
                        + " most used entries, or set device to \"web\"");
            }
            int index = 0;
            for (JsonNode entry : navigation) {
                String target = text(entry, "screen");
                if (!hasText(target) || !screenNames.contains(normalize(target))) {
                    errors.add(here + ": navigation[" + index + "] screen '" + (target == null ? "" : target)
                            + "' is not declared in screens[] -- suggestedFix: add that screen to screens[], "
                            + "or point the entry at an existing screen");
                }
                checkExpression(here + ": navigation[" + index + "] badge", entry.get("badge"), errors);
                index++;
            }
        }
    }

    // ------------------------------------------------------------------ screens

    /**
     * D-1 phase 1: {@code admin} turns a {@code list} screen into the generated back-office pages of its concept (what an
     * autoPanel used to declare). The screen names the concept; {@code admin} holds the pages' own settings and has no
     * {@code name}/{@code concept}/{@code aggregate} of its own (the schema refuses them). Its inner shape is the autoPanel schema and
     * {@code PanelValidation.validateAutoPanels} checks it.
     */
    private static void validateAdminBlock(String here, JsonNode screen, String kind, String conceptName, List<String> errors) {
        JsonNode admin = screen.get("admin");
        if (admin == null) {
            return;
        }
        if (!admin.isObject()) {
            errors.add(here + ": 'admin' must be an object -- suggestedFix: write the generated pages' settings as an object "
                    + "(route, surfaces, selection, detail, transaction, prompt), or remove 'admin'");
            return;
        }
        if (kind != null && !"list".equals(kind) && !"workbench".equals(kind)) {
            errors.add(here + ": 'admin' is only allowed on kind \"list\" or \"workbench\" (this screen is " + kind
                    + ") -- suggestedFix: remove 'admin', or change the screen's kind to \"list\" (a concept's pages) "
                    + "or \"workbench\" (an aggregate's workbench)");
        }
        if (!hasText(conceptName)) {
            errors.add(here + ": 'admin' needs the screen's 'concept' -- suggestedFix: set \"concept\" to the concept whose "
                    + "back-office pages these are");
        }
    }

    /**
     * D-1 phase 3: {@code panel} turns a {@code custom} screen into a hand-written panel (what a root {@code panels[]} entry
     * declared). The screen supplies the name, so {@code panel} never repeats it; the panel's own body is the panel schema and
     * {@code PanelValidation} checks it. A panel is served at its own route, so it is not a client component either.
     */
    private static void validatePanelBlock(String here, JsonNode screen, String kind, List<String> errors) {
        JsonNode panel = screen.get("panel");
        if (panel == null) {
            return;
        }
        if (!panel.isObject()) {
            errors.add(here + ": 'panel' must be an object -- suggestedFix: write the panel's settings as an object "
                    + "(route, title, dataSources, layout, fieldBindings, actions), or remove 'panel'");
            return;
        }
        if (kind != null && !"custom".equals(kind)) {
            errors.add(here + ": 'panel' is only allowed on kind \"custom\" (this screen is " + kind
                    + ") -- suggestedFix: remove 'panel', or change the screen's kind to \"custom\"");
        }
        if (panel.has("name")) {
            errors.add(here + ": 'panel.name' is not allowed -- suggestedFix: remove it; the screen's own 'name' is used");
        }
        if (screen.has("admin")) {
            errors.add(here + ": a screen cannot carry both 'panel' and 'admin' -- suggestedFix: split them into two screens");
        }
        if (hasText(text(screen, "component"))) {
            errors.add(here + ": a screen with a 'panel' cannot also have a 'component' -- suggestedFix: remove 'component' "
                    + "(a panel is served at its own route), or remove 'panel'");
        }
    }

    private static void validateScreen(
            JsonNode screen,
            Set<String> screenNames,
            Map<String, JsonNode> screensByLower,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, QueryAst> queriesByLower,
            Map<String, FlowAst> flowsByLower,
            List<String> errors
    ) {
        String name = text(screen, "name");
        String here = "Screen " + (hasText(name) ? name : "<unnamed>");
        String kind = text(screen, "kind");
        if (!hasText(kind) || !KINDS.contains(kind)) {
            errors.add(here + ": kind '" + (kind == null ? "" : kind) + "' is not one of " + String.join(", ", sortedKinds())
                    + " -- suggestedFix: set kind to one of the closed archetypes, or use kind \"custom\" with a registered component");
            kind = null;
        }

        // The data source: a concept, or a query. Each must exist when named; the archetypes that show rows need one.
        String conceptName = text(screen, "concept");
        String queryName = text(screen, "query");
        ConceptAst concept = null;
        if (hasText(conceptName)) {
            concept = entitiesByLower.get(normalize(conceptName));
            if (concept == null) {
                errors.add(here + ": concept '" + conceptName + "' is not declared -- suggestedFix: declare the concept in concepts[], or fix the name in 'concept'");
            }
        }
        QueryAst query = null;
        if (hasText(queryName)) {
            query = queriesByLower.get(normalize(queryName));
            if (query == null) {
                errors.add(here + ": query '" + queryName + "' is not declared -- suggestedFix: declare the query in queries[], or fix the name in 'query'");
            }
        }
        if (kind != null && KINDS_NEEDING_DATA.contains(kind) && !hasText(conceptName) && !hasText(queryName)) {
            errors.add(here + ": a " + kind + " screen needs a 'concept' or a 'query' -- suggestedFix: set \"concept\" to the concept whose rows this screen shows, or \"query\" to a queries[] name");
        }
        validateAdminBlock(here, screen, kind, conceptName, errors);
        validatePanelBlock(here, screen, kind, errors);
        // Fields are checked against the screen's concept; a plain query shows its concept's fields, a grouped or
        // computed one shows other columns, so it is not field-checked.
        ConceptAst fieldSource = fieldSourceOf(concept, query, entitiesByLower);
        Set<String> fieldNames = new HashSet<>();
        if (fieldSource != null) {
            for (FieldAst field : fieldSource.getFields()) {
                if (hasText(field.getName())) {
                    fieldNames.add(normalize(field.getName()));
                }
            }
        }
        String conceptLabel = fieldSource == null ? "" : fieldSource.getName();

        JsonNode columns = screen.get("columns");
        if (columns != null && columns.isArray()) {
            if (columns.size() > MAX_COLUMNS) {
                errors.add(here + ": columns has " + columns.size() + " entries but a screen shows at most " + MAX_COLUMNS
                        + " -- suggestedFix: keep the " + MAX_COLUMNS + " most useful columns; the generated back office still lists every field");
            }
            checkFieldEntries(here, "columns", columns, fieldSource != null, fieldNames, conceptLabel, errors);
        }
        JsonNode fields = screen.get("fields");
        if (fields != null && fields.isArray()) {
            checkFieldEntries(here, "fields", fields, fieldSource != null, fieldNames, conceptLabel, errors);
        }
        String groupBy = text(screen, "groupBy");
        if (hasText(groupBy) && fieldSource != null && !fieldNames.contains(normalize(groupBy))) {
            errors.add(here + ": groupBy field '" + groupBy + "' does not exist on concept " + conceptLabel
                    + " -- suggestedFix: set groupBy to an enum or lifecycle field declared on " + conceptLabel);
        }

        if ("picker".equals(kind)) {
            checkPicker(here, screen, conceptName, fieldSource != null, fieldNames, conceptLabel, errors);
        } else {
            checkExpression(here + ": where", screen.get("where"), errors);
            for (String pickerOnly : PICKER_ONLY_KEYS) {
                if (screen.has(pickerOnly) && kind != null) {
                    errors.add(here + ": '" + pickerOnly + "' is only allowed on kind \"picker\" (this screen is " + kind
                            + ") -- suggestedFix: remove '" + pickerOnly + "', or change the screen's kind to \"picker\"");
                }
            }
        }
        if ("document".equals(kind)) {
            checkDocument(here, screen, conceptName, errors);
        } else if (kind != null) {
            for (String documentOnly : DOCUMENT_ONLY_KEYS) {
                if (screen.has(documentOnly)) {
                    errors.add(here + ": '" + documentOnly + "' is only allowed on kind \"document\" (this screen is " + kind
                            + ") -- suggestedFix: remove '" + documentOnly + "', or change the screen's kind to \"document\"");
                }
            }
        }
        checkExpression(here + ": visibleWhen", screen.get("visibleWhen"), errors);
        JsonNode params = screen.get("params");
        if (params != null && params.isObject()) {
            params.fields().forEachRemaining(entry -> checkExpression(here + ": params." + entry.getKey(), entry.getValue(), errors));
        }
        JsonNode countdown = screen.get("countdown");
        if (countdown != null && countdown.isObject()) {
            checkExpression(here + ": countdown.until", countdown.get("until"), errors);
        }

        JsonNode map = screen.get("map");
        if (map != null && map.isObject()) {
            checkMap(here, map, fieldSource != null, fieldNames, conceptLabel, errors);
        }

        JsonNode widgets = screen.get("widgets");
        if (widgets != null && widgets.isArray()) {
            int index = 0;
            for (JsonNode widget : widgets) {
                checkWidgetValue(here + ": widgets[" + index + "] value", widget.get("value"), errors);
                String embedded = text(widget, "screen");
                if (hasText(embedded) && !screenNames.contains(normalize(embedded))) {
                    errors.add(here + ": widgets[" + index + "] screen '" + embedded + "' is not declared in screens[] -- suggestedFix: "
                            + "add that screen to screens[], or point the widget at an existing screen");
                }
                String widgetGroupBy = text(widget, "groupBy");
                if (hasText(widgetGroupBy) && hasText(embedded) && screensByLower.containsKey(normalize(embedded))) {
                    JsonNode linked = screensByLower.get(normalize(embedded));
                    ConceptAst linkedSource = fieldSourceOf(
                            entitiesByLower.get(normalize(text(linked, "concept"))),
                            queriesByLower.get(normalize(text(linked, "query"))),
                            entitiesByLower);
                    if (linkedSource != null) {
                        Set<String> linkedFields = new HashSet<>();
                        for (FieldAst field : linkedSource.getFields()) {
                            if (hasText(field.getName())) {
                                linkedFields.add(normalize(field.getName()));
                            }
                        }
                        if (!linkedFields.contains(normalize(widgetGroupBy))) {
                            errors.add(here + ": widgets[" + index + "] groupBy field '" + widgetGroupBy
                                    + "' does not exist on concept " + linkedSource.getName() + " (the concept of screen '" + embedded
                                    + "') -- suggestedFix: set groupBy to a field declared on " + linkedSource.getName());
                        }
                    }
                }
                index++;
            }
        }

        JsonNode actions = screen.get("actions");
        if (actions != null && actions.isArray()) {
            int index = 0;
            for (JsonNode action : actions) {
                validateAction(here + ": actions[" + index + "]", action, screenNames, flowsByLower, errors);
                index++;
            }
        }

        boolean hasComponent = hasText(text(screen, "component"));
        if ("custom".equals(kind) && !hasComponent && !screen.has("panel")) {
            errors.add(here + ": a custom screen needs a 'component' or a 'panel' -- suggestedFix: set \"component\" to the name registered with window.npdev.regions.register, or declare the panel it is");
        } else if (hasComponent && kind != null && !"custom".equals(kind)) {
            errors.add(here + ": 'component' is only allowed on kind \"custom\" (this screen is " + kind
                    + ") -- suggestedFix: remove 'component', or change the screen's kind to \"custom\"");
        }
    }

    /**
     * P5-T9b: a document screen is the authoring form of the old top-level {@code documents[]} entry (the semantic rules --
     * aggregate binding, bands, logo, report columns -- live in {@code DocumentValidation}, which runs over the documents
     * {@code ModelAst.getDocuments()} derives from these screens). Here: it is driven by a concept, and it carries none of
     * the list/detail machinery, which would be silently ignored when printing.
     */
    private static void checkDocument(String here, JsonNode screen, String conceptName, List<String> errors) {
        if (!hasText(conceptName)) {
            errors.add(here + ": a document screen needs a 'concept' -- suggestedFix: set \"concept\" to the concept whose records this document prints");
        }
        for (String key : DOCUMENT_INAPPLICABLE_KEYS) {
            if (screen.has(key)) {
                errors.add(here + ": '" + key + "' does not apply to kind \"document\" -- suggestedFix: remove '" + key
                        + "' (a document prints 'bands' over an aggregate, or a report's 'printColumns'; the back office still lists every field)");
            }
        }
        JsonNode groupBy = screen.get("groupBy");
        if (groupBy != null && !groupBy.isArray()) {
            errors.add(here + ": groupBy on a document must be an array of field names -- suggestedFix: set \"groupBy\" to for example [\"status\"]");
        }
    }

    /**
     * P5-T9a: a picker screen is the authoring form of the old top-level {@code selectors[]} entry. It is driven by a
     * concept (never a query), {@code multiSelect} is a boolean, {@code filters} (searchable fields), the
     * {@code returnMapping} values and the {@code orderBy} entries name fields of that concept, and {@code where} is
     * a picker predicate (the shared {@link PickerFilterGrammar}: {@code field == literal && ...}), not a screen expression.
     */
    private static void checkPicker(
            String here,
            JsonNode screen,
            String conceptName,
            boolean knowsFields,
            Set<String> fieldNames,
            String conceptLabel,
            List<String> errors
    ) {
        if (!hasText(conceptName)) {
            errors.add(here + ": a picker screen needs a 'concept' -- suggestedFix: set \"concept\" to the concept whose rows the user picks from");
        }
        if (hasText(text(screen, "query"))) {
            errors.add(here + ": a picker screen is driven by a 'concept', not a 'query' -- suggestedFix: remove 'query' and set \"concept\"");
        }
        JsonNode multiSelect = screen.get("multiSelect");
        if (multiSelect != null && !multiSelect.isBoolean()) {
            errors.add(here + ": multiSelect must be true or false -- suggestedFix: set \"multiSelect\" to true (pick many) or false (pick one)");
        }
        JsonNode filters = screen.get("filters");
        if (filters != null) {
            if (!filters.isArray()) {
                errors.add(here + ": filters must be an array of field names -- suggestedFix: set \"filters\" to the fields the user can search, for example [\"name\"]");
            } else {
                int index = 0;
                for (JsonNode filter : filters) {
                    checkPickerField(here + ": filters[" + index + "]", filter, knowsFields, fieldNames, conceptLabel, errors);
                    index++;
                }
            }
        }
        JsonNode returnMapping = screen.get("returnMapping");
        if (returnMapping != null) {
            if (!returnMapping.isObject()) {
                errors.add(here + ": returnMapping must be an object {returnedName: fieldName} -- suggestedFix: set \"returnMapping\" to for example {\"sku\": \"sku\"}");
            } else {
                returnMapping.fields().forEachRemaining(entry ->
                        checkPickerField(here + ": returnMapping." + entry.getKey(), entry.getValue(), knowsFields, fieldNames, conceptLabel, errors));
            }
        }
        JsonNode orderBy = screen.get("orderBy");
        if (orderBy != null) {
            if (orderBy.isArray()) {
                int index = 0;
                for (JsonNode entry : orderBy) {
                    checkPickerField(here + ": orderBy[" + index + "]", entry, knowsFields, fieldNames, conceptLabel, errors);
                    index++;
                }
            } else {
                checkPickerField(here + ": orderBy", orderBy, knowsFields, fieldNames, conceptLabel, errors);
            }
        }
        String where = text(screen, "where");
        if (hasText(where)) {
            try {
                for (PickerFilterGrammar.Clause clause : PickerFilterGrammar.parse(where)) {
                    if (knowsFields && !fieldNames.contains(normalize(clause.field()))) {
                        errors.add(here + ": where references field '" + clause.field() + "', which does not exist on concept "
                                + conceptLabel + " -- suggestedFix: use a field declared on " + conceptLabel);
                    }
                }
            } catch (PickerFilterGrammar.UnsupportedFilterException e) {
                errors.add(here + ": where is not a valid picker predicate (" + e.getMessage() + ") -- suggestedFix: "
                        + "write clauses joined by && such as \"discontinued == false\" (no || and no functions)");
            }
        }
    }

    /** A picker's filters / returnMapping / orderBy entries are plain field names of the screen's concept. */
    private static void checkPickerField(
            String where,
            JsonNode value,
            boolean knowsFields,
            Set<String> fieldNames,
            String conceptLabel,
            List<String> errors
    ) {
        if (!value.isTextual() || !hasText(value.asText())) {
            errors.add(where + " must be a field name -- suggestedFix: use a field declared on " + (hasText(conceptLabel) ? conceptLabel : "the concept"));
            return;
        }
        // orderBy may carry a direction ("-sku", "sku desc"); the name is what must exist.
        String name = value.asText().trim().replaceFirst("^[-+]", "").replaceFirst("(?i)\\s+(asc|desc)$", "");
        if (knowsFields && !fieldNames.contains(normalize(name))) {
            errors.add(where + " field '" + name + "' does not exist on concept " + conceptLabel
                    + " -- suggestedFix: use a field declared on " + conceptLabel);
        }
    }

    /** The concept whose fields a screen shows: its own concept, else the concept of a plain (ungrouped) query. */
    private static ConceptAst fieldSourceOf(ConceptAst concept, QueryAst query, Map<String, ConceptAst> entitiesByLower) {
        if (concept != null) {
            return concept;
        }
        if (query != null && hasText(query.concept()) && query.groupBy().isEmpty()
                && query.aggregates().isEmpty() && query.computed().isEmpty() && query.lookups().isEmpty()) {
            return entitiesByLower.get(normalize(query.concept()));
        }
        return null;
    }

    /** FORM P5-T4: the data points of a widget {@code value}: one expression, {label: expression}, or [{label, value}]. */
    private static void checkWidgetValue(String where, JsonNode value, List<String> errors) {
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            checkExpression(where, value.asText(), errors);
        } else if (value.isObject()) {
            value.fields().forEachRemaining(entry -> checkExpression(
                    where + "[" + entry.getKey() + "]", entry.getValue(), errors));
        } else if (value.isArray()) {
            int point = 0;
            for (JsonNode item : value) {
                checkExpression(where + "[" + point + "].value", item == null ? null : item.get("value"), errors);
                point++;
            }
        }
    }

    /** A {@code moveTo} is usually a plain column value; only text that reads as an expression is parse-checked. */
    private static boolean looksLikeExpression(String value) {
        return value.contains("$") || value.contains("(") || value.contains("==") || value.contains("!=")
                || value.contains("&&") || value.contains("||") || value.contains("?");
    }

    private static void validateAction(
            String here,
            JsonNode action,
            Set<String> screenNames,
            Map<String, FlowAst> flowsByLower,
            List<String> errors
    ) {
        String flowName = text(action, "flow");
        if (!hasText(flowName)) {
            errors.add(here + ": flow is required -- suggestedFix: set \"flow\" to the name of the flow this button starts");
        } else {
            FlowAst flow = flowsByLower.get(normalize(flowName));
            if (flow == null) {
                errors.add(here + ": flow '" + flowName + "' is not declared -- suggestedFix: declare the flow in flows[], or fix the name in 'flow'");
            } else if ("internal".equals(flow.getExpose())) {
                errors.add(here + ": flow '" + flowName + "' is expose internal, so a screen cannot start it -- suggestedFix: "
                        + "set the flow's expose to \"ui\" (or \"api\"), or start a different flow");
            }
        }
        checkExpression(here + " visibleWhen", action.get("visibleWhen"), errors);
        String moveTo = text(action, "moveTo");
        if (hasText(moveTo) && looksLikeExpression(moveTo)) {
            checkExpression(here + " moveTo", moveTo, errors);
        }
        JsonNode input = action.get("input");
        if (input != null && input.isObject()) {
            input.fields().forEachRemaining(entry -> checkExpression(here + " input." + entry.getKey(), entry.getValue(), errors));
        }
        String then = text(action, "then");
        if (hasText(then) && !screenNames.contains(normalize(then))) {
            errors.add(here + ": then screen '" + then + "' is not declared in screens[] -- suggestedFix: "
                    + "add that screen to screens[], or point 'then' at an existing screen");
        }
    }

    private static void checkMap(
            String here,
            JsonNode map,
            boolean knowsFields,
            Set<String> fieldNames,
            String conceptLabel,
            List<String> errors
    ) {
        for (String key : List.of("lat", "lng")) {
            checkFieldReference(here + ": map." + key, text(map, key), knowsFields, fieldNames, conceptLabel, errors);
        }
        checkExpression(here + ": map.label", map.get("label"), errors);
        JsonNode pickPoints = map.get("pickPoints");
        if (pickPoints != null && pickPoints.isArray()) {
            int index = 0;
            for (JsonNode point : pickPoints) {
                for (String key : List.of("lat", "lng")) {
                    checkFieldReference(here + ": map.pickPoints[" + index + "]." + key, text(point, key), knowsFields,
                            fieldNames, conceptLabel, errors);
                }
                index++;
            }
        }
    }

    /** One {@code columns[]}/{@code fields[]} entry per element: a field name, or {@code {value, label, format...}}. */
    private static void checkFieldEntries(
            String here,
            String key,
            JsonNode entries,
            boolean knowsFields,
            Set<String> fieldNames,
            String conceptLabel,
            List<String> errors
    ) {
        int index = 0;
        for (JsonNode entry : entries) {
            String value = entry.isTextual() ? entry.asText() : text(entry, "value");
            checkFieldReference(here + ": " + key + "[" + index + "]", value, knowsFields, fieldNames, conceptLabel, errors);
            index++;
        }
    }

    /** A plain identifier must be a field of the concept; anything else is an expression and is only parse-checked. */
    private static void checkFieldReference(
            String where,
            String value,
            boolean knowsFields,
            Set<String> fieldNames,
            String conceptLabel,
            List<String> errors
    ) {
        if (!hasText(value)) {
            return;
        }
        if (FIELD_NAME.matcher(value).matches()) {
            if (knowsFields && !fieldNames.contains(normalize(value))) {
                errors.add(where + " field '" + value + "' does not exist on concept " + conceptLabel
                        + " -- suggestedFix: use a field declared on " + conceptLabel + ", or write an expression");
            }
            return;
        }
        checkExpression(where, value, errors);
    }

    private static void checkExpression(String where, JsonNode expression, List<String> errors) {
        if (expression != null && expression.isTextual()) {
            checkExpression(where, expression.asText(), errors);
        }
    }

    private static void checkExpression(String where, String expression, List<String> errors) {
        if (!hasText(expression)) {
            return;
        }
        try {
            ComputedExpression.parse(expression);
        } catch (ComputedExpression.ExpressionException invalid) {
            errors.add(where + " is not a valid expression '" + expression + "': " + invalid.getMessage()
                    + " -- suggestedFix: fix the expression syntax (fields, $row/$form/$user references, literals, operators)");
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static List<String> sortedKinds() {
        return KINDS.stream().sorted().toList();
    }
}
