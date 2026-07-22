package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.JavaIdentifierSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaIdentifierSupportTest {

    @Test
    void normalizesNamespacedConceptToClassName() {
        assertEquals("CatalogProduct", JavaIdentifierSupport.className("catalog::Product"));
    }

    @Test
    void normalizesHyphenatedPackAliasToClassName() {
        assertEquals("SalesCoreProduct", JavaIdentifierSupport.className("sales-core::Product"));
    }
}
