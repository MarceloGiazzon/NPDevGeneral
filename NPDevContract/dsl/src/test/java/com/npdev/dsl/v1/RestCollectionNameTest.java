package com.npdev.dsl.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.npdev.dsl.v1.compiled.EnglishPlurals;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** REG-270: a concept ending in "y" was served at /ticket_categorys. */
class RestCollectionNameTest {

    @Test
    void consonantYBecomesIes() {
        assertEquals("ticket_categories", SqlIdentifierSupport.restCollectionName("TicketCategory", List.of()));
        assertEquals("companies", SqlIdentifierSupport.restCollectionName("Company", List.of()));
        assertEquals("policies", SqlIdentifierSupport.restCollectionName("Policy", List.of()));
        assertEquals("care_log_entries", SqlIdentifierSupport.restCollectionName("CareLogEntry", List.of()));
    }

    @Test
    void vowelYKeepsPlainS() {
        assertEquals("days", SqlIdentifierSupport.restCollectionName("Day", List.of()));
        assertEquals("surveys", SqlIdentifierSupport.restCollectionName("Survey", List.of()));
    }

    @Test
    void sibilantEndingsTakeEs() {
        assertEquals("boxes", SqlIdentifierSupport.restCollectionName("Box", List.of()));
        assertEquals("batches", SqlIdentifierSupport.restCollectionName("Batch", List.of()));
        assertEquals("dishes", SqlIdentifierSupport.restCollectionName("Dish", List.of()));
    }

    @Test
    void ordinaryAndAlreadyPluralNamesAreUnchangedFromTheOldRule() {
        assertEquals("widgets", SqlIdentifierSupport.restCollectionName("Widget", List.of()));
        assertEquals("orders", SqlIdentifierSupport.restCollectionName("Orders", List.of()));
        assertEquals("identity_users", SqlIdentifierSupport.restCollectionName("identity::User", List.of()));
    }

    @Test
    void routeAgreesWithTheTablePluralForEveryNonYNonSibilantName() {
        for (String name : List.of("Widget", "Order", "CanaryTask", "identity::User", "catalog::Product", "Orders")) {
            assertEquals(
                    SqlIdentifierSupport.aliasPreservingTableName(name, List.of()),
                    SqlIdentifierSupport.restCollectionName(name, List.of()),
                    name);
        }
    }

    @Test
    void physicalTablePluralIsDeliberatelyUnchanged() {
        assertEquals("ticket_categorys", SqlIdentifierSupport.toSnakePlural("TicketCategory"));
    }

    @Test
    void conceptEndpointBaseUsesTheRestCollectionName() {
        assertEquals("/api/concepts/ticket_categories",
                SqlIdentifierSupport.conceptEndpointBase("TicketCategory", List.of()));
    }

    @Test
    void englishPluralsKeepsCaseOfTheStemForDisplayLabels() {
        assertEquals("Categories", EnglishPlurals.pluralize("Category"));
        assertEquals("Ticket Boxes", EnglishPlurals.pluralize("Ticket Box"));
        assertEquals("Leave Requests", EnglishPlurals.pluralize("Leave Request"));
        assertEquals("Status", EnglishPlurals.pluralize("Status"));
    }
}
