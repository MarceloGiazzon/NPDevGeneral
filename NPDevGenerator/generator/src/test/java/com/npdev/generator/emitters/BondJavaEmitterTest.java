package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BondJavaEmitterTest {
    @TempDir
    Path tempDir;

    @Test
    void scalarBondJavaFieldsUseResolvedAnchorType() throws Exception {
        CompiledModel model = modelWithNaturalKeyBond(false);
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());

        new EntityEmitter(templates, writer).emit(model);
        new DtoEmitter(templates, writer).emit(model);
        new ServiceEmitter(templates, writer).emit(model);
        new ControllerEmitter(templates, writer).emit(model);

        String entity = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/entities/Invoice.java"));
        String createDto = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/dtos/InvoiceCreateRequest.java"));
        String updateDto = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/dtos/InvoiceUpdateRequest.java"));
        String service = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/services/InvoiceServiceBase.java"));
        String controller = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/controllers/InvoiceController.java"));

        assertTrue(entity.contains("private String productId;"), entity);
        assertTrue(createDto.contains("private String productId;"), createDto);
        assertTrue(updateDto.contains("private String productId;"), updateDto);
        assertTrue(service.contains("GeneratedCrudRuntimeSupport.mapDataIntegrityViolation"), service);
        assertTrue(service.contains("listByProductId"), service);
        assertTrue(controller.contains("@GetMapping(\"/by/productId/{value}\")"), controller);
        assertFalse(entity.contains("private java.util.UUID productId;"), entity);
    }

    /**
     * RUN-28 (2026-08-25 remediation plan W2.2): {@code listBy*} reference finders no longer fetch
     * the whole tenant table and filter in Java -- they push an {@code EQ_CI} (case-insensitive,
     * trimmed, string-cast equality -- the exact rule the old Java-side {@code uniqueValuesEqual}
     * applied) filter down through {@code ConceptGateway#query}, the platform's last remaining
     * unbounded read path before this fix. Supersedes the RUN-1-era test that used to lock in the
     * OPPOSITE exemption (deliberately staying unbounded because the old fetch-then-filter shape
     * could not tolerate a cap without silently dropping matches) -- that shape is gone, not merely
     * capped, so this test locks in the pushdown instead.
     */
    @Test
    void listByFinderPushesEqCiThroughConceptGatewayQueryNotAnUnboundedFetch() throws Exception {
        CompiledModel model = modelWithNaturalKeyBond(false);
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());

        new EntityEmitter(templates, writer).emit(model);
        new DtoEmitter(templates, writer).emit(model);
        new ServiceEmitter(templates, writer).emit(model);
        new ControllerEmitter(templates, writer).emit(model);

        String service = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/services/InvoiceServiceBase.java"));

        int methodStart = service.indexOf("listByProductId(Object value)");
        assertTrue(methodStart >= 0, service);
        int bodyStart = service.indexOf('{', methodStart);
        int bodyEnd = service.indexOf("\n    }", bodyStart);
        String methodBody = service.substring(bodyStart, bodyEnd < 0 ? service.length() : bodyEnd);

        assertTrue(methodBody.contains("conceptGateway.query("), methodBody);
        assertTrue(methodBody.contains("Filter.eqCaseInsensitive(\"productId\", value)"), methodBody);
        assertFalse(methodBody.contains("findAllUnboundedFromConceptStore()"), methodBody);
        assertFalse(methodBody.contains("uniqueValuesEqual"), methodBody);

        // The method itself no longer exists anywhere in the file -- RUN-28 removed it as dead code
        // once listBy* (its only caller) stopped needing it. Checked as a call/declaration site
        // (trailing '('), not a bare substring: a javadoc comment explaining WHY it was removed
        // legitimately still names it in prose.
        assertFalse(service.contains("findAllUnboundedFromConceptStore("), service);
        assertFalse(service.contains("uniqueValuesEqual("), service);

        // Return type widened so truncation is signallable -- see ConceptListSlice's own javadoc.
        assertTrue(service.contains(
                "public com.npdev.kernel.concepts.ConceptListSlice<Invoice> listByProductId(Object value)"),
                service);

        String controller = Files.readString(
                tempDir.resolve("src/main/java/com/npdev/generated/controllers/InvoiceController.java"));
        assertTrue(controller.contains("listByBase(service.listByProductId(value))"), controller);
    }

    @Test
    void manyToManyBondIsNotEmittedAsScalarCrudField() throws Exception {
        CompiledModel model = modelWithNaturalKeyBond(true);
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(tempDir, new RegenerationPolicy());

        new EntityEmitter(templates, writer).emit(model);
        new DtoEmitter(templates, writer).emit(model);
        new ServiceEmitter(templates, writer).emit(model);
        new ControllerEmitter(templates, writer).emit(model);

        String entity = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/entities/Invoice.java"));
        String createDto = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/dtos/InvoiceCreateRequest.java"));
        String service = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/services/InvoiceServiceBase.java"));
        String controller = Files.readString(tempDir.resolve("src/main/java/com/npdev/generated/controllers/InvoiceController.java"));

        assertFalse(entity.contains("productId"), entity);
        assertFalse(createDto.contains("productId"), createDto);
        assertTrue(service.contains("listProductIdMembers"), service);
        assertTrue(service.contains("addProductIdMember"), service);
        assertTrue(controller.contains("@GetMapping(\"/{id}/productId\")"), controller);
        assertTrue(controller.contains("@PostMapping(\"/{id}/productId/{targetAnchor}\")"), controller);
    }

    private static CompiledModel modelWithNaturalKeyBond(boolean multiple) {
        CompiledField skuAnchor = new CompiledField(
                "skuId", "string", "String", false, true, true,
                List.of(), null, null, null, null, List.of(), null, "anchor");
        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        skuAnchor
                )
        );
        CompiledReferenceSemantics viaSku = new CompiledReferenceSemantics(
                "Product", multiple, null, List.of(), List.of(), null, null, List.of(), null, null,
                "skuId", "restrict");
        CompiledConcept invoice = new CompiledConcept(
                "Invoice", "Invoice", "invoices",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("productId", "reference", "java.util.UUID", false, false, false,
                                List.of(), "Product", viaSku, null, null, List.of(), null, null)
                )
        );
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(product.getName(), product);
        concepts.put(invoice.getName(), invoice);
        return new CompiledModel("default", "v1", concepts);
    }
}
