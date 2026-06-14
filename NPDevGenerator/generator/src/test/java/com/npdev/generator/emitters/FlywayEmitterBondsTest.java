package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: bonds become real foreign keys in the generated schema. DB is the source of
 * truth for referential integrity. Anchor type drives the FK column type; onDelete maps
 * to ON DELETE; a connectable natural-key anchor gets a unique index to reference.
 */
class FlywayEmitterBondsTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsForeignKeysWithAnchorTypeAndOnDeletePolicy() throws Exception {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, false, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");

        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        skuAnchor
                )
        );

        // Natural-key bond: Invoice.productId -> Product.skuId, ON DELETE CASCADE.
        CompiledReferenceSemantics viaSku = new CompiledReferenceSemantics(
                "Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "cascade");
        // Default id bond: Invoice.ownerId -> Product.id, ON DELETE RESTRICT (default).
        CompiledReferenceSemantics viaId = new CompiledReferenceSemantics(
                "Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
                null, null);

        CompiledConcept invoice = new CompiledConcept(
                "Invoice", "Invoice", "invoices",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productId", "reference", "java.util.UUID", false, false, false,
                                List.of(), "Product", viaSku, null, null, List.of(), null, null),
                        new CompiledField("ownerId", "reference", "java.util.UUID", false, false, false,
                                List.of(), "Product", viaId, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(invoice.getName(), invoice);
        CompiledModel model = new CompiledModel("default", "v1", concepts);

        Path file = new FlywayEmitter().emitRepeatableSchema(model, tempDir);
        String sql = Files.readString(file);

        // Natural-key anchor drives the FK column type (string -> VARCHAR), not the default UUID.
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS product_id VARCHAR(255);"),
                "product_id should take the skuId anchor type. SQL:\n" + sql);
        // Default-id bond keeps the uuid column type.
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS owner_id UUID;"),
                "owner_id should stay UUID. SQL:\n" + sql);

        // A connectable anchor must be a UNIQUE CONSTRAINT (not just an index) so it is a valid FK
        // target on H2 (a unique index alone triggers H2 error 90057). It also backs uniqueness.
        assertTrue(sql.contains("ALTER TABLE products ADD CONSTRAINT IF NOT EXISTS uq_products_sku_id UNIQUE (sku_id);"),
                "connectable anchor skuId needs a UNIQUE constraint. SQL:\n" + sql);
        assertFalse(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS ux_products_sku_id"),
                "connectable anchor should use a UNIQUE constraint, not a unique index. SQL:\n" + sql);

        // Real foreign keys, with the right anchor column and ON DELETE policy.
        assertTrue(sql.contains(
                        "ALTER TABLE invoices ADD CONSTRAINT IF NOT EXISTS fk_invoices_product_id"
                        + " FOREIGN KEY (product_id) REFERENCES products (sku_id) ON UPDATE CASCADE ON DELETE CASCADE;"),
                "expected cascade FK to products(sku_id). SQL:\n" + sql);
        assertTrue(sql.contains(
                        "ALTER TABLE invoices ADD CONSTRAINT IF NOT EXISTS fk_invoices_owner_id"
                        + " FOREIGN KEY (owner_id) REFERENCES products (id) ON DELETE RESTRICT;"),
                "expected restrict FK to products(id). SQL:\n" + sql);
    }

    @Test
    void emitsJunctionTableForMultipleReferenceBond() throws Exception {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, false, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");
        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        skuAnchor
                )
        );
        CompiledReferenceSemantics viaSkuSet = new CompiledReferenceSemantics(
                "Product", true, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "cascade");
        CompiledConcept invoice = new CompiledConcept(
                "Invoice", "Invoice", "invoices",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productId", "reference", "java.util.UUID", false, false, false,
                                List.of(), "Product", viaSkuSet, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(invoice.getName(), invoice);
        CompiledModel model = new CompiledModel("default", "v1", concepts);

        Path file = new FlywayEmitter().emitRepeatableSchema(model, tempDir);
        String sql = Files.readString(file);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS invoices_product_id"), sql);
        assertTrue(sql.contains("source_id UUID NOT NULL"), sql);
        assertTrue(sql.contains("target_sku_id VARCHAR(255) NOT NULL"), sql);
        assertTrue(sql.contains("PRIMARY KEY (source_id, target_sku_id)"), sql);
        assertTrue(sql.contains("REFERENCES invoices (id) ON DELETE CASCADE"), sql);
        assertTrue(sql.contains("REFERENCES products (sku_id) ON UPDATE CASCADE ON DELETE CASCADE"), sql);
        assertTrue(!sql.contains("ALTER TABLE invoices ADD COLUMN IF NOT EXISTS product_id"),
                "N:M port should not be emitted as a scalar column. SQL:\n" + sql);
    }

    /**
     * Per-bond nullability. A bond is mandatory or optional independently of being a bond:
     * the port field's {@code required} flag drives the column's NOT NULL constraint.
     * Invoice.userId is mandatory (every invoice needs a user) -> NOT NULL + ON DELETE RESTRICT.
     * Invoice.productId is optional (an ad-hoc line with just a name/price needs no product)
     * -> nullable + ON DELETE SET NULL. The two must not be conflated.
     */
    @Test
    void requiredBondColumnIsNotNullWhileOptionalBondColumnStaysNullable() throws Exception {
        CompiledConcept user = new CompiledConcept(
                "User", "User", "users",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );

        // Mandatory bond: required port, default-id anchor, RESTRICT.
        CompiledReferenceSemantics userRef = new CompiledReferenceSemantics(
                "User", false, null, List.of(), List.of(), null, null, List.of(), null, null,
                null, "restrict");
        // Optional bond: non-required port, default-id anchor, SET NULL on delete.
        CompiledReferenceSemantics productRef = new CompiledReferenceSemantics(
                "Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
                null, "nullify");

        CompiledConcept invoice = new CompiledConcept(
                "Invoice", "Invoice", "invoices",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        // required = true -> NOT NULL
                        new CompiledField("userId", "reference", "java.util.UUID", false, true, false,
                                List.of(), "User", userRef, null, null, List.of(), null, null),
                        // required = false -> nullable
                        new CompiledField("productId", "reference", "java.util.UUID", false, false, false,
                                List.of(), "Product", productRef, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(user.getName(), user);
        concepts.put(product.getName(), product);
        concepts.put(invoice.getName(), invoice);
        CompiledModel model = new CompiledModel("default", "v1", concepts);

        Path file = new FlywayEmitter().emitRepeatableSchema(model, tempDir);
        String sql = Files.readString(file);

        // Mandatory bond: NOT NULL constraint present.
        assertTrue(sql.contains("ALTER TABLE invoices ALTER COLUMN user_id SET NOT NULL;"),
                "required bond user_id must be NOT NULL. SQL:\n" + sql);
        // Optional bond: no NOT NULL constraint, so the column accepts null.
        assertFalse(sql.contains("ALTER COLUMN product_id SET NOT NULL"),
                "optional bond product_id must stay nullable. SQL:\n" + sql);

        // Integrity policy matches each bond's nature.
        assertTrue(sql.contains(
                        "ALTER TABLE invoices ADD CONSTRAINT IF NOT EXISTS fk_invoices_user_id"
                        + " FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;"),
                "mandatory bond should restrict deletes. SQL:\n" + sql);
        assertTrue(sql.contains(
                        "ALTER TABLE invoices ADD CONSTRAINT IF NOT EXISTS fk_invoices_product_id"
                        + " FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE SET NULL;"),
                "optional bond should null out on delete. SQL:\n" + sql);
    }

    @Test
    void emitsPackNamespacedNaturalKeyBondSql() throws Exception {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, true, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");
        CompiledConcept product = new CompiledConcept(
                "cat::Product", "CatProduct", "cat_products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        skuAnchor
                )
        );
        CompiledReferenceSemantics viaSku = new CompiledReferenceSemantics(
                "cat::Product", false, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "restrict");
        CompiledConcept variant = new CompiledConcept(
                "cat::Variant", "CatVariant", "cat_variants",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productSku", "reference", "java.util.UUID", false, true, false,
                                List.of(), "cat::Product", viaSku, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(variant.getName(), variant);
        Path file = new FlywayEmitter().emitRepeatableSchema(new CompiledModel("default", "v1", concepts), tempDir);
        String sql = Files.readString(file);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS cat_products"), sql);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS cat_variants"), sql);
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS product_sku VARCHAR(255);"), sql);
        assertTrue(sql.contains("REFERENCES cat_products (sku_id) ON UPDATE CASCADE ON DELETE RESTRICT"), sql);
        assertTrue(!sql.contains("::"), sql);
    }

    @Test
    void longJunctionIdentifiersUseStableHashSuffix() throws Exception {
        String longSourceTable = "source_" + "very_long_segment_".repeat(5);
        String longFieldName = "related" + "ProductSegment".repeat(5);
        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledReferenceSemantics many = new CompiledReferenceSemantics(
                "Product", true, null, List.of(), List.of(), null, null, List.of(), null, null,
                null, "cascade");
        CompiledConcept source = new CompiledConcept(
                "Source", "Source", longSourceTable,
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField(longFieldName, "reference", "java.util.UUID", false, false, false,
                                List.of(), "Product", many, null, null, List.of(), null, null)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(source.getName(), source);
        concepts.put(product.getName(), product);
        Path file = new FlywayEmitter().emitRepeatableSchema(new CompiledModel("default", "v1", concepts), tempDir);
        String sql = Files.readString(file);
        String junction = SqlIdentifierSupport.junctionTableName(source, source.getFields().get(1));

        assertEquals(63, junction.length());
        assertTrue(junction.matches("[a-z0-9_]+_[0-9a-f]{8}"), junction);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + junction), sql);
    }
}
