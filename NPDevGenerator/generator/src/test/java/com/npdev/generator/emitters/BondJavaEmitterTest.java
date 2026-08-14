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
     * RUN-1 (R8a): {@code listBy*} reference finders must stay on the platform's original UNBOUNDED
     * fetch, never the cap introduced for {@link #scalarBondJavaFieldsUseResolvedAnchorType} to lock
     * around -- caught in review before merge: a reference finder filters AFTER the fetch
     * ({@code findAllX(...).stream().filter(matches value)}), so routing it through the capped path
     * would silently drop a legitimate match whose id sorts past the cap, with no truncation signal
     * at all (this method returns a raw {@code List}, not a {@code ConceptListSlice}). Locks in the
     * exemption explicitly so a future refactor that re-shares the helper trips this test.
     */
    @Test
    void listByFinderStaysOnTheUnboundedFetchNotTheCappedOne() throws Exception {
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

        assertTrue(methodBody.contains("findAllUnboundedFromConceptStore()"), methodBody);
        assertFalse(methodBody.contains("findAllSliceFromConceptStore"), methodBody);
        assertFalse(methodBody.contains("findAllFromConceptStore()"), methodBody);
        assertFalse(methodBody.contains("listCapped"), methodBody);
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
