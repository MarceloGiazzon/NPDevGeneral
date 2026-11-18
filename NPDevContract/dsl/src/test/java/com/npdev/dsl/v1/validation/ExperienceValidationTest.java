package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P5-T1: one failing case per {@link ExperienceValidation} rule, plus the valid base and the "no experiences
 * validates exactly as before" guarantee. Rules the schema also enforces (kind set, column cap, required name) are
 * exercised against a hand-built {@link ModelAst}, because the schema pass would refuse the model before the
 * validator ever ran.
 */
class ExperienceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE = """
            {
              "namespace": "experiences.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "roles": [
                { "name": "Rider", "concepts": { "Ride": ["read", "create", "update"] } },
                { "name": "Driver", "concepts": { "Ride": ["read"] } }
              ],
              "concepts": [
                { "name": "Ride", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "status", "type": "string" },
                  { "name": "fareCents", "type": "integer" },
                  { "name": "pickupLat", "type": "decimal" },
                  { "name": "pickupLng", "type": "decimal" }
                ] }
              ],
              "queries": [
                { "name": "OpenRides", "concept": "Ride", "where": "status == 'REQUESTED'" }
              ],
              "flows": [
                { "name": "RequestRide", "expose": "ui", "steps": [
                  { "name": "answer", "type": "set", "value": "'ok'", "output": "$answer" },
                  { "name": "done", "type": "return", "value": "$answer" } ] },
                { "name": "SweepRides", "expose": "internal", "steps": [
                  { "name": "answer", "type": "set", "value": "'ok'", "output": "$answer" },
                  { "name": "done", "type": "return", "value": "$answer" } ] }
              ],
              "experiences": [
                { "name": "rider", "title": "Ride", "for": ["Rider"], "device": "mobile", "home": "my-trips",
                  "navigation": [ { "screen": "my-trips", "label": "Trips", "icon": "list" }, { "screen": "request" } ] }
              ],
              "screens": [
                { "name": "my-trips", "kind": "list", "concept": "Ride", "where": "status != 'CANCELLED'",
                  "columns": ["status", { "value": "fareCents", "format": "money", "unitScale": 0.01 }] },
                { "name": "request", "kind": "form", "concept": "Ride", "fields": ["pickupLat", "pickupLng"],
                  "map": { "pickPoints": [ { "name": "pickup", "lat": "pickupLat", "lng": "pickupLng" } ] },
                  "actions": [ { "label": "Request", "flow": "RequestRide", "style": "primary",
                                 "input": { "pickupLat": "$form.pickupLat" }, "then": "my-trips" } ] }
              ]
            }
            """;

    @TempDir
    Path temp;

    // ------------------------------------------------------------------ the valid base and backward compatibility

    @Test
    void theBaseModelValidates() throws Exception {
        assertEquals(List.of(), errorsFor(root -> { }));
    }

    @Test
    void aModelWithoutExperiencesOrScreensValidatesExactlyAsBefore() throws Exception {
        List<String> errors = errorsFor(root -> {
            root.remove("experiences");
            root.remove("screens");
        });
        assertEquals(List.of(), errors);
    }

    @Test
    void aQueryDrivenListScreenTakesItsFieldsFromTheQueryConcept() throws Exception {
        List<String> errors = errorsFor(root -> {
            ObjectNode screen = screen(root, "my-trips");
            screen.remove("concept");
            screen.put("query", "OpenRides");
        });
        assertEquals(List.of(), errors);

        List<String> bad = errorsFor(root -> {
            ObjectNode screen = screen(root, "my-trips");
            screen.remove("concept");
            screen.put("query", "OpenRides");
            ((ArrayNode) screen.get("columns")).add("nope");
        });
        assertHas(bad, "field 'nope' does not exist on concept Ride");
    }

    // ------------------------------------------------------------------ experiences

    @Test
    void experienceNamesMustBeUnique() throws Exception {
        List<String> errors = errorsFor(root -> {
            ArrayNode experiences = (ArrayNode) root.get("experiences");
            experiences.add(experiences.get(0).deepCopy());
        });
        assertHas(errors, "duplicate experience name", "suggestedFix");
    }

    @Test
    void everyRoleInForMustBeDeclared() throws Exception {
        List<String> errors = errorsFor(root ->
                ((ArrayNode) experience(root, "rider").get("for")).add("Pilot"));
        assertHas(errors, "role 'Pilot' in 'for' is not declared in roles[]", "suggestedFix");
    }

    @Test
    void homeMustNameAnExistingScreen() throws Exception {
        List<String> errors = errorsFor(root -> experience(root, "rider").put("home", "nowhere"));
        assertHas(errors, "home screen 'nowhere' is not declared in screens[]", "suggestedFix");
    }

    @Test
    void everyNavigationEntryMustNameAnExistingScreen() throws Exception {
        List<String> errors = errorsFor(root ->
                ((ArrayNode) experience(root, "rider").get("navigation")).addObject().put("screen", "ghost"));
        assertHas(errors, "navigation[2] screen 'ghost' is not declared in screens[]", "suggestedFix");
    }

    @Test
    void navigationHasAtMostFiveEntriesOnMobileOrBoth() throws Exception {
        Consumer<ObjectNode> sixEntries = root -> {
            ArrayNode navigation = (ArrayNode) experience(root, "rider").get("navigation");
            while (navigation.size() < 6) {
                navigation.addObject().put("screen", "my-trips");
            }
        };
        assertHas(errorsFor(sixEntries), "navigation has 6 entries but a mobile experience shows at most 5", "suggestedFix");

        assertHas(errorsFor(root -> {
            sixEntries.accept(root);
            experience(root, "rider").remove("device"); // the default device is both
        }), "navigation has 6 entries but a both experience shows at most 5");

        assertEquals(List.of(), errorsFor(root -> {
            sixEntries.accept(root);
            experience(root, "rider").put("device", "web"); // a web experience has no tab bar
        }));
    }

    // ------------------------------------------------------------------ screens

    @Test
    void screenNamesMustBeUnique() throws Exception {
        List<String> errors = errorsFor(root -> {
            ArrayNode screens = (ArrayNode) root.get("screens");
            screens.add(screens.get(0).deepCopy());
        });
        assertHas(errors, "Screen my-trips: duplicate screen name", "suggestedFix");
    }

    @Test
    void aScreenThatShowsRowsNeedsAConceptOrAQuery() throws Exception {
        List<String> errors = errorsFor(root -> screen(root, "my-trips").remove("concept"));
        assertHas(errors, "a list screen needs a 'concept' or a 'query'", "suggestedFix");
    }

    @Test
    void theNamedConceptAndQueryMustExist() throws Exception {
        assertHas(errorsFor(root -> screen(root, "my-trips").put("concept", "Phantom")),
                "concept 'Phantom' is not declared", "suggestedFix");
        assertHas(errorsFor(root -> {
            ObjectNode screen = screen(root, "my-trips");
            screen.remove("concept");
            screen.put("query", "NoSuchQuery");
        }), "query 'NoSuchQuery' is not declared", "suggestedFix");
    }

    @Test
    void everyFieldNamedByColumnsFieldsAndGroupByMustExistOnTheConcept() throws Exception {
        assertHas(errorsFor(root -> ((ArrayNode) screen(root, "my-trips").get("columns")).add("ghost")),
                "columns[2] field 'ghost' does not exist on concept Ride", "suggestedFix");
        assertHas(errorsFor(root -> ((ArrayNode) screen(root, "request").get("fields")).add(
                        MAPPER.createObjectNode().put("value", "ghost"))),
                "fields[2] field 'ghost' does not exist on concept Ride");
        assertHas(errorsFor(root -> {
            ObjectNode screen = screen(root, "my-trips");
            screen.put("kind", "board");
            screen.put("groupBy", "ghost");
        }), "groupBy field 'ghost' does not exist on concept Ride", "suggestedFix");
        assertHas(errorsFor(root -> screen(root, "request").with("map").put("lat", "ghostLat")),
                "map.lat field 'ghostLat' does not exist on concept Ride");
    }

    @Test
    void aDottedOrComputedColumnIsOnlyParseCheckedNotFieldChecked() throws Exception {
        assertEquals(List.of(), errorsFor(root -> ((ArrayNode) screen(root, "my-trips").get("columns")).add(
                MAPPER.createObjectNode().put("value", "ride.fareCents"))));
        assertHas(errorsFor(root -> ((ArrayNode) screen(root, "my-trips").get("columns")).add(
                        MAPPER.createObjectNode().put("value", "fareCents + "))),
                "columns[2] is not a valid expression");
    }

    @Test
    void everyActionFlowMustExistAndBeExposedAsApiOrUi() throws Exception {
        assertHas(errorsFor(root -> action(root, "request").put("flow", "NoSuchFlow")),
                "actions[0]: flow 'NoSuchFlow' is not declared", "suggestedFix");
        assertHas(errorsFor(root -> action(root, "request").put("flow", "SweepRides")),
                "flow 'SweepRides' is expose internal, so a screen cannot start it", "suggestedFix");
    }

    @Test
    void anActionThenMustNameAnExistingScreen() throws Exception {
        assertHas(errorsFor(root -> action(root, "request").put("then", "ghost")),
                "then screen 'ghost' is not declared in screens[]", "suggestedFix");
    }

    @Test
    void everyExpressionMustParse() throws Exception {
        assertHas(errorsFor(root -> screen(root, "my-trips").put("where", "status ==")),
                "Screen my-trips: where is not a valid expression 'status =='", "suggestedFix");
        assertHas(errorsFor(root -> screen(root, "my-trips").put("visibleWhen", "&& status")),
                "visibleWhen is not a valid expression");
        assertHas(errorsFor(root -> action(root, "request").put("visibleWhen", "(status")),
                "actions[0] visibleWhen is not a valid expression");
        assertHas(errorsFor(root -> ((ObjectNode) action(root, "request").get("input")).put("pickupLat", "$form.")),
                "actions[0] input.pickupLat is not a valid expression");
        assertHas(errorsFor(root -> screen(root, "my-trips").with("countdown").put("until", "addSeconds(createdAt,")),
                "countdown.until is not a valid expression");
        assertHas(errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "tile").put("value", "count('Ride',");
        }), "widgets[0] value is not a valid expression");
        assertHas(errorsFor(root -> ((ObjectNode) experience(root, "rider").get("navigation").get(0)).put("badge", "1 +")),
                "navigation[0] badge is not a valid expression");
    }

    @Test
    void aWidgetThatEmbedsAScreenMustNameAnExistingOne() throws Exception {
        assertHas(errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "table").put("screen", "ghost");
        }), "widgets[0] screen 'ghost' is not declared in screens[]", "suggestedFix");
    }

    @Test
    void aChartWidgetValueMayBeAnObjectMapOrAnArrayAndEveryExpressionInItParses() throws Exception {
        assertEquals(List.of(), errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            ArrayNode widgets = dashboard.putArray("widgets");
            widgets.addObject().put("kind", "chart").put("chart", "bar")
                    .putObject("value").put("New", "count('Ride', status == 'NEW')").put("Done", "count('Ride', status == 'DONE')");
            ObjectNode points = widgets.addObject().put("kind", "chart").put("chart", "donut");
            points.putArray("value").addObject().put("label", "All").put("value", "count('Ride')");
        }));
        assertHas(errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "chart").putObject("value").put("New", "count('Ride',");
        }), "widgets[0] value[New] is not a valid expression");
        assertHas(errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "chart").putArray("value")
                    .addObject().put("label", "x").put("value", "1 +");
        }), "widgets[0] value[0].value is not a valid expression");
    }

    @Test
    void aWidgetGroupByMustBeAFieldOfTheEmbeddedScreensConceptWhenThatConceptIsKnown() throws Exception {
        assertEquals(List.of(), errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "chart").put("screen", "my-trips").put("groupBy", "status");
        }));
        assertHas(errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "chart").put("screen", "my-trips").put("groupBy", "ghostField");
        }), "widgets[0] groupBy field 'ghostField' does not exist on concept Ride", "suggestedFix");
        // No linked screen (or one that does not resolve): nothing to check against, so no error.
        assertEquals(List.of(), errorsFor(root -> {
            ObjectNode dashboard = ((ArrayNode) root.get("screens")).addObject();
            dashboard.put("name", "today").put("kind", "dashboard");
            dashboard.putArray("widgets").addObject().put("kind", "chart").put("groupBy", "anything");
        }));
    }

    @Test
    void aMoveToThatLooksLikeAnExpressionMustParseButAPlainColumnValueIsFine() throws Exception {
        assertEquals(List.of(), errorsFor(root -> action(root, "request").put("style", "move").put("moveTo", "In progress")));
        assertEquals(List.of(), errorsFor(root -> action(root, "request").put("style", "move").put("moveTo", "DONE")));
        assertEquals(List.of(), errorsFor(root -> action(root, "request").put("style", "move").put("moveTo", "$row.status == 'A' ? 'B' : 'C'")));
        assertHas(errorsFor(root -> action(root, "request").put("style", "move").put("moveTo", "$row.status == ")),
                "actions[0] moveTo is not a valid expression", "suggestedFix");
    }

    @Test
    void componentIsAllowedOnlyOnCustomScreensAndRequiredThere() throws Exception {
        assertHas(errorsFor(root -> screen(root, "my-trips").put("component", "fancy")),
                "'component' is only allowed on kind \"custom\"", "suggestedFix");
        assertHas(errorsFor(root -> ((ArrayNode) root.get("screens")).addObject()
                        .put("name", "extra").put("kind", "custom")),
                "a custom screen needs a 'component'", "suggestedFix");
        assertEquals(List.of(), errorsFor(root -> ((ArrayNode) root.get("screens")).addObject()
                .put("name", "extra").put("kind", "custom").put("component", "fancy")));
    }

    // ------------------------------------------------------------------ picker screens (P5-T9a: the old selectors[])

    private static ObjectNode picker(ObjectNode root) {
        ObjectNode picker = ((ArrayNode) root.get("screens")).addObject();
        picker.put("name", "RidePicker").put("kind", "picker").put("concept", "Ride");
        picker.put("multiSelect", true);
        picker.putArray("filters").add("status");
        picker.putArray("columns").add("status").add("fareCents");
        picker.putArray("orderBy").add("status");
        picker.putObject("returnMapping").put("rideId", "id");
        picker.put("where", "status != 'CANCELLED' && fareCents != 0");
        return picker;
    }

    @Test
    void aWellFormedPickerScreenValidatesAndAPascalCaseNameIsFine() throws Exception {
        assertEquals(List.of(), errorsFor(root -> picker(root)));
    }

    @Test
    void aPickerNeedsAConceptAndIsNotDrivenByAQuery() throws Exception {
        assertHas(errorsFor(root -> picker(root).remove("concept")),
                "a picker screen needs a 'concept'", "suggestedFix");
        assertHas(errorsFor(root -> picker(root).put("query", "OpenRides")),
                "a picker screen is driven by a 'concept', not a 'query'", "suggestedFix");
    }

    @Test
    void aPickersFiltersReturnMappingOrderByAndWhereMustNameFieldsOfItsConcept() throws Exception {
        assertHas(errorsFor(root -> ((ArrayNode) picker(root).get("filters")).add("nope")),
                "filters[1] field 'nope' does not exist on concept Ride");
        assertHas(errorsFor(root -> ((ObjectNode) picker(root).get("returnMapping")).put("x", "nope")),
                "returnMapping.x field 'nope' does not exist on concept Ride");
        assertHas(errorsFor(root -> ((ArrayNode) picker(root).get("orderBy")).add("nope")),
                "orderBy[1] field 'nope' does not exist on concept Ride");
        assertHas(errorsFor(root -> picker(root).put("where", "nope == 'x'")),
                "where references field 'nope', which does not exist on concept Ride");
        // a direction on orderBy is allowed; the name before it is what must exist
        assertEquals(List.of(), errorsFor(root -> ((ArrayNode) picker(root).get("orderBy")).add("-fareCents").add("status desc")));
    }

    @Test
    void aPickersWhereIsAPickerPredicateAndMultiSelectIsABoolean() throws Exception {
        assertHas(errorsFor(root -> picker(root).put("where", "status == 'A' || status == 'B'")),
                "where is not a valid picker predicate", "suggestedFix");
        // the schema refuses a non-boolean multiSelect first; the validator must too for a model built without it
        ObjectNode notBoolean = (ObjectNode) MAPPER.readTree(
                "{\"name\": \"P\", \"kind\": \"picker\", \"concept\": \"Ride\", \"multiSelect\": \"yes\"}");
        List<String> errors = new ArrayList<>();
        ExperienceValidation.validate(directModel(List.of(), List.of(notBoolean)), Map.of(), errors);
        assertHas(errors, "multiSelect must be true or false", "suggestedFix");
    }

    @Test
    void pickerOnlyPropertiesAreRefusedOnOtherKinds() throws Exception {
        assertHas(errorsFor(root -> screen(root, "my-trips").put("multiSelect", true)),
                "'multiSelect' is only allowed on kind \"picker\"", "suggestedFix");
        assertHas(errorsFor(root -> screen(root, "my-trips").putArray("filters").add("status")),
                "'filters' is only allowed on kind \"picker\"");
        assertHas(errorsFor(root -> screen(root, "my-trips").putObject("returnMapping").put("a", "status")),
                "'returnMapping' is only allowed on kind \"picker\"");
    }

    @Test
    void aSelectorRefResolvesAgainstAPickerScreenAndAMissingOneIsRefused() throws Exception {
        // concept Ride gets a field that points at the picker through picker.selectorRef
        assertEquals(List.of(), errorsFor(root -> {
            picker(root);
            ((ArrayNode) root.withArray("concepts").get(0).get("fields")).addObject()
                    .put("name", "other").put("type", "reference").put("ref", "Ride").putObject("picker").put("selectorRef", "RidePicker");
        }));
        assertHas(errorsFor(root -> ((ArrayNode) root.withArray("concepts").get(0).get("fields")).addObject()
                        .put("name", "other").put("type", "reference").put("ref", "Ride").putObject("picker").put("selectorRef", "RidePicker")),
                "names undeclared picker screen 'RidePicker'", "suggestedFix");
    }

    @Test
    void theExpressionShapesTheWorkedRideHailingExampleUsesAllParse() {
        for (String expression : List.of(
                "riderAccount == $user.id && status in ['REQUESTED','ASSIGNED','ACCEPTED','IN_PROGRESS']",
                "count('Ride', riderAccount == $user.id && status in ['REQUESTED'])",
                "$row.status == 'COMPLETED' && $row.driverStars == null",
                "$row.status in ['REQUESTED','ASSIGNED']",
                "addSeconds(createdAt, 20)",
                "name + ' (' + status + ')'",
                "'Pick-up'",
                "$form.pickupLat",
                "count('Ride', status == 'NO_DRIVER' && isToday(createdAt))")) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> com.npdev.dsl.v1.expr.ComputedExpression.parse(expression), expression);
        }
    }

    // ------------------------------------------------------------------ rules the schema also enforces

    @Test
    void theValidatorAlsoRefusesWhatTheSchemaRefusesForAModelBuiltWithoutTheSchemaPass() throws Exception {
        ObjectNode unnamedScreen = (ObjectNode) MAPPER.readTree("{\"kind\": \"list\", \"concept\": \"Ride\"}");
        ObjectNode unknownKind = (ObjectNode) MAPPER.readTree("{\"name\": \"odd\", \"kind\": \"carousel\"}");
        ObjectNode tooManyColumns = (ObjectNode) MAPPER.readTree(
                "{\"name\": \"wide\", \"kind\": \"list\", \"query\": \"Q\", \"columns\": [\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\"]}");
        ObjectNode unnamedExperience = (ObjectNode) MAPPER.readTree("{\"for\": [\"Rider\"], \"home\": \"odd\"}");

        List<String> errors = new ArrayList<>();
        ExperienceValidation.validate(
                directModel(List.of(unnamedExperience), List.of(unnamedScreen, unknownKind, tooManyColumns)),
                Map.of(), errors);

        assertHas(errors,
                "Screen: name is required",
                "kind 'carousel' is not one of",
                "columns has 8 entries but a screen shows at most 7",
                "Experience: name is required");
        errors.forEach(error -> assertTrue(error.contains("-- suggestedFix:"), "no suggestedFix on: " + error));
    }

    // ------------------------------------------------------------------ helpers

    private List<String> errorsFor(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(BASE);
        mutation.accept(root);
        Path model = temp.resolve("model-" + System.nanoTime() + ".json");
        Files.writeString(model, MAPPER.writeValueAsString(root));
        ModelAst ast = new JsonModelParser().parse(model);
        return new ArrayList<>(new SemanticValidator().validateWithWarnings(ast).getErrors());
    }

    private static ModelAst directModel(List<JsonNode> experiences, List<JsonNode> screens) {
        return new ModelAst("direct.demo", "3.0.0", "1.0", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(),
                null, null, experiences, screens);
    }

    private static ObjectNode experience(ObjectNode root, String name) {
        return named((ArrayNode) root.get("experiences"), name);
    }

    private static ObjectNode screen(ObjectNode root, String name) {
        return named((ArrayNode) root.get("screens"), name);
    }

    private static ObjectNode action(ObjectNode root, String screenName) {
        return (ObjectNode) screen(root, screenName).get("actions").get(0);
    }

    private static ObjectNode named(ArrayNode array, String name) {
        for (JsonNode node : array) {
            if (name.equals(node.path("name").asText())) {
                return (ObjectNode) node;
            }
        }
        throw new IllegalArgumentException("no entry named " + name);
    }

    private static void assertHas(List<String> errors, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(errors.stream().anyMatch(error -> error.contains(fragment)),
                    "expected an error containing '" + fragment + "', got: " + errors);
        }
    }
}
