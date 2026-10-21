package com.npdev.runtime.support;

import com.npdev.kernel.concepts.ConceptStoreUniqueViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REG-257: the in-process store's unique-index refusal maps to the same 409 unique_integrity_failed a database gives. */
class GeneratedCrudRuntimeSupportStoreUniqueViolationMappingTest {

    @Test
    void storeUniqueIndexViolationMapsTo409UniqueIntegrityFailedNamingTheFirstIndexField() {
        Optional<GeneratedCrudRuntimeSupport.InvariantViolationException> mapped =
                GeneratedCrudRuntimeSupport.mapDataIntegrityViolation("WidgetOrderLine", null,
                        new ConceptStoreUniqueViolationException(
                                "WidgetOrderLine", "ux_widget_order_line_sku", List.of("widgetOrderId", "sku")));

        assertTrue(mapped.isPresent());
        assertEquals(409, mapped.get().statusCode());
        GeneratedCrudRuntimeSupport.InvariantViolationDetail detail = mapped.get().violations().get(0);
        assertEquals("unique_integrity_failed", detail.code());
        assertEquals("widgetOrderId", detail.path());
    }
}
