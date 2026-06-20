package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackBondEmitterTest {
    @TempDir
    Path temp;

    @Test
    void packNaturalKeyBondGeneratesFkSqlAndAnchorTypedJava() throws Exception {
        Path modelPath = writePackBondModel();
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(modelPath);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected pack bond model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path outRoot = temp.resolve("app");
        new SchemaRealizationEmitter().emit(compiled, outRoot, plan(), modelPath);
        String sql = Files.readString(
                outRoot.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS cat_products"), sql);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS cat_variants"), sql);
        assertTrue(sql.contains("product_sku VARCHAR(255)"), sql);
        assertTrue(sql.contains("REFERENCES cat_products (sku_id)") && sql.contains("ON UPDATE CASCADE"), sql);
        assertTrue(sql.contains("DO $$"), sql);
        assertTrue(sql.contains("INFORMATION_SCHEMA.TABLE_CONSTRAINTS"),
                "DO $$ guard must query INFORMATION_SCHEMA, not pg_constraint. SQL:\n" + sql);
        assertFalse(sql.contains("pg_constraint"),
                "pg_constraint is a PostgreSQL-only catalog absent in H2; must not appear. SQL:\n" + sql);
        assertTrue(sql.contains("ALTER TABLE orders") && sql.contains("ADD CONSTRAINT fk_orders_product_sku")
                && sql.contains("FOREIGN KEY (product_sku)") && sql.contains("ON DELETE RESTRICT"), sql);
        assertFalse(sql.contains("ADD CONSTRAINT IF NOT EXISTS"), sql);
        assertFalse(sql.contains("::"), sql);

        Path generated = temp.resolve("generated");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(generated, new RegenerationPolicy());
        new EntityEmitter(templates, writer).emit(compiled);
        new DtoEmitter(templates, writer).emit(compiled);
        new ServiceEmitter(templates, writer).emit(compiled);
        new ControllerEmitter(templates, writer).emit(compiled);

        Path variantEntity = generated.resolve("src/main/java/com/npdev/generated/entities/CatVariant.java");
        Path orderEntity = generated.resolve("src/main/java/com/npdev/generated/entities/Order.java");
        assertTrue(Files.exists(variantEntity), "Expected generated namespaced class file");
        assertTrue(Files.exists(orderEntity), "Expected generated root class file");
        assertTrue(Files.readString(variantEntity).contains("private String productSku;"));
        assertTrue(Files.readString(orderEntity).contains("private String productSku;"));
    }

    private Path writePackBondModel() throws Exception {
        write("packs/catalog/fragments/product.json", """
                {
                  "concepts": [
                    { "name": "Product", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "skuId", "type": "string", "required": true, "unique": true, "connectable": "anchor" }
                    ] }
                  ]
                }
                """);
        write("packs/catalog/fragments/variant.json", """
                {
                  "concepts": [
                    { "name": "Variant", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      {
                        "name": "productSku",
                        "type": "reference",
                        "required": true,
                        "reference": { "target": "Product", "via": "skuId", "onDelete": "cascade" }
                      }
                    ] }
                  ]
                }
                """);
        write("packs/catalog/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "fragments": [
                    { "$ref": "fragments/product.json" },
                    { "$ref": "fragments/variant.json" }
                  ]
                }
                """);
        return write("model.json", """
                {
                  "namespace": "pack.bond.generator",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [
                    { "$ref": "packs/catalog/pack.json", "as": "cat" }
                  ],
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      {
                        "name": "productSku",
                        "type": "reference",
                        "reference": { "target": "cat::Product", "via": "skuId", "onDelete": "restrict" }
                      }
                    ] }
                  ]
                }
                """);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "pack-bond-test",
                DatabaseEngine.POSTGRES,
                DatabaseEngine.POSTGRES.storageMode(),
                true,
                "pack-bond-test",
                "pack-bond-test",
                "test",
                temp.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:postgresql://localhost:5432/pack-bond-test",
                "org.postgresql.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                false,
                true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "test-fingerprint",
                temp.resolve("database.json"),
                List.of("test")
        );
    }
}
