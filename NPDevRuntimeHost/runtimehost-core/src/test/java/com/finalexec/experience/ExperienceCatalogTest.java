package com.finalexec.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.finalexec.experience.ExperienceCatalog.FlowFacts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * FORM P5-T2: the decisions behind {@code GET /api/x/me} and {@code POST /api/x/flows/<flow>/execute}.
 */
class ExperienceCatalogTest {

    /** Two experiences, declared zeta (Boss) before alpha (Agent + Boss); alpha reaches `ticket` only via `then`. */
    private static final String RESOLVED = """
            {
              "experiences": [
                { "name": "zeta", "for": ["Boss"], "home": "board" },
                { "name": "alpha", "for": ["Agent", "Boss"], "home": "tickets",
                  "navigation": [ { "screen": "tickets" } ] }
              ],
              "screens": [
                { "name": "board", "kind": "dashboard", "widgets": [ { "kind": "list", "screen": "tickets" } ] },
                { "name": "tickets", "kind": "list", "dataUrl": "/api/concepts/tickets",
                  "actions": [ { "label": "Open", "flow": "OpenTicket", "then": "ticket", "startUrl": "/api/x/flows/OpenTicket/execute" } ] },
                { "name": "ticket", "kind": "detail", "dataUrl": "/api/concepts/tickets",
                  "actions": [ { "label": "Close", "flow": "CloseTicket" } ] },
                { "name": "orphan", "kind": "form",
                  "actions": [ { "label": "Never", "flow": "OrphanFlow" } ] }
              ]
            }
            """;

    private static final Map<String, String> EXPOSE = Map.of(
            "OpenTicket", "api",
            "CloseTicket", "ui",
            "OrphanFlow", "ui",
            "InternalOnly", "internal",
            "UnreferencedApi", "api");

    private final ExperienceCatalog catalog = ExperienceCatalog.fromJson(RESOLVED);

    private static final Function<String, Optional<FlowFacts>> LOOKUP = name -> EXPOSE.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .findFirst()
            .map(entry -> new FlowFacts(entry.getKey(), entry.getValue()));

    // ------------------------------------------------------------------ me

    @Test
    void meChoosesTheFirstDeclaredExperienceWhoseForIntersectsTheRoles() {
        // Boss matches both; zeta is declared first, so zeta wins (declaration order, not alphabetical).
        Map<String, Object> boss = catalog.me(Set.of("BOSS"), "bob");
        assertEquals("zeta", ((JsonNode) boss.get("experience")).path("name").asText());

        // Agent matches only alpha; the role compare is case-insensitive.
        Map<String, Object> agent = catalog.me(Set.of("agent"), "amy");
        assertEquals("alpha", ((JsonNode) agent.get("experience")).path("name").asText());
    }

    @Test
    void meReturnsOnlyTheScreensTheExperienceReaches() {
        Map<String, Object> agent = catalog.me(Set.of("AGENT"), "amy");
        @SuppressWarnings("unchecked")
        List<JsonNode> screens = (List<JsonNode>) agent.get("screens");
        // home + navigation (tickets) + action.then (ticket); the unreachable `orphan` and `board` are not sent.
        assertEquals(List.of("tickets", "ticket"), screens.stream().map(s -> s.path("name").asText()).toList());
        assertEquals("/api/concepts/tickets", screens.get(0).path("dataUrl").asText(), "resolved fields are passed through");

        @SuppressWarnings("unchecked")
        List<JsonNode> bossScreens = (List<JsonNode>) catalog.me(Set.of("BOSS"), "bob").get("screens");
        // zeta: home board, then the board's widget screen tickets, then its action's `then`.
        assertEquals(List.of("board", "tickets", "ticket"), bossScreens.stream().map(s -> s.path("name").asText()).toList());
    }

    @Test
    void meForARoleWithoutAnExperienceIsNullButStillReportsTheRoles() {
        Map<String, Object> admin = catalog.me(Set.of("ADMIN", "USER"), "root");
        assertNull(admin.get("experience"));
        assertEquals(List.of(), admin.get("screens"));
        assertEquals(List.of("ADMIN", "USER"), ((Map<?, ?>) admin.get("user")).get("roles"));
        assertEquals("root", ((Map<?, ?>) admin.get("user")).get("id"), "the caller's actor id ($user.id)");
    }

    @Test
    void anAppWithoutExperiencesAnswersNullForEveryone() {
        ExperienceCatalog none = ExperienceCatalog.empty();
        assertTrue(none.isEmpty());
        assertNull(none.me(Set.of("ADMIN"), "root").get("experience"));
        assertFalse(none.hasExperience("alpha"));
        assertTrue(ExperienceCatalog.fromJson("").isEmpty());
    }

    @Test
    void anExperienceExistsByItsExactName() {
        assertTrue(catalog.hasExperience("alpha"));
        assertTrue(catalog.hasExperience("zeta"));
        assertFalse(catalog.hasExperience("nope"));
        assertFalse(catalog.hasExperience(null));
    }

    // ------------------------------------------------------------------ flow start

    @Test
    void aReferencedUiFlowIsAuthorisedForARoleOfTheExperience() {
        assertEquals(Optional.of("CloseTicket"), catalog.authorizeFlowStart(Set.of("AGENT"), "CloseTicket", LOOKUP));
        // An action nested behind `then` counts, and the flow name compares case-insensitively but is returned canonical.
        assertEquals(Optional.of("CloseTicket"), catalog.authorizeFlowStart(Set.of("AGENT"), "closeticket", LOOKUP));
        // An api flow that the experience references is allowed too.
        assertEquals(Optional.of("OpenTicket"), catalog.authorizeFlowStart(Set.of("AGENT"), "OpenTicket", LOOKUP));
    }

    @Test
    void anInternalFlowIsNeverAuthorisedEvenWhenAScreenNamesIt() {
        ExperienceCatalog namesInternal = ExperienceCatalog.fromJson("""
                { "experiences": [ { "name": "alpha", "for": ["Agent"], "home": "s" } ],
                  "screens": [ { "name": "s", "kind": "list", "actions": [ { "label": "x", "flow": "InternalOnly" } ] } ] }
                """);
        assertEquals(Optional.empty(), namesInternal.authorizeFlowStart(Set.of("AGENT"), "InternalOnly", LOOKUP));
    }

    @Test
    void anApiFlowNoScreenOfTheCallersExperienceReferencesIsNotAuthorised() {
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("AGENT"), "UnreferencedApi", LOOKUP));
        // referenced only by an UNREACHABLE screen (orphan) -- not a screen of any experience.
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("AGENT"), "OrphanFlow", LOOKUP));
    }

    @Test
    void aRoleOutsideTheExperienceCannotStartItsFlows() {
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("USER"), "CloseTicket", LOOKUP));
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("ADMIN", "USER"), "CloseTicket", LOOKUP));
    }

    @Test
    void aMissingFlowOrBlankNameIsNotAuthorised() {
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("AGENT"), "NoSuchFlow", LOOKUP));
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("AGENT"), "", LOOKUP));
        assertEquals(Optional.empty(), catalog.authorizeFlowStart(Set.of("AGENT"), null, LOOKUP));
    }

    @Test
    void aFlowWithNoDeclaredExposeCountsAsApi() {
        Function<String, Optional<FlowFacts>> noExpose = name -> Optional.of(new FlowFacts(name, null));
        assertEquals(Optional.of("CloseTicket"), catalog.authorizeFlowStart(Set.of("AGENT"), "CloseTicket", noExpose));
    }

    /** A list row tap opens the first declared detail screen of the same concept: it must be sent and its actions authorisable. */
    @Test
    void aListRowTapReachesTheFirstDetailScreenOfTheSameConcept() {
        ExperienceCatalog c = ExperienceCatalog.fromJson("""
                { "experiences": [ { "name": "shop", "for": ["Clerk"], "home": "items" } ],
                  "screens": [
                    { "name": "items", "kind": "list", "concept": "Item" },
                    { "name": "item-a", "kind": "detail", "concept": "Item", "actions": [ { "label": "Go", "flow": "DoA" } ] },
                    { "name": "item-b", "kind": "detail", "concept": "Item", "actions": [ { "label": "Go", "flow": "DoB" } ] },
                    { "name": "other", "kind": "detail", "concept": "Other", "actions": [ { "label": "Go", "flow": "DoC" } ] }
                  ] }
                """);
        Function<String, Optional<FlowFacts>> lookup = name -> Optional.of(new FlowFacts(name, "ui"));
        List<String> names = new java.util.ArrayList<>();
        for (Object screen : (List<?>) c.me(Set.of("Clerk"), "u1").get("screens")) {
            names.add(((JsonNode) screen).path("name").asText());
        }
        assertEquals(List.of("items", "item-a"), names, "only the FIRST detail of the list's concept, nothing of another concept");
        assertTrue(c.authorizeFlowStart(Set.of("Clerk"), "DoA", lookup).isPresent());
        assertTrue(c.authorizeFlowStart(Set.of("Clerk"), "DoB", lookup).isEmpty());
        assertTrue(c.authorizeFlowStart(Set.of("Clerk"), "DoC", lookup).isEmpty());
    }
}
