package com.npdev.generator.api;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-44 — a model may not declare row-level access rules that the generated app would never enforce.
 *
 * <p>With {@code crud.kernelControlled: false} the generator omits every authorization call from the
 * generated service: the coarse concept permission checks, the row-level {@code access.write} gate,
 * and mutation audit. Before this check the combination compiled cleanly and shipped an app whose
 * declared security rules were decoration.</p>
 *
 * <p>The tests below pin both directions, because a check that only ever throws is as useless as one
 * that never does: the offending combination must fail, and each of the two legitimate shapes — rules
 * with the flag on, no rules with the flag off — must still generate.</p>
 */
class UnenforceableAccessRuleCheckTest {

    private static final String WITH_ACCESS_RULES = """
            {
              "namespace": "reg44",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Ticket",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "ownerId", "type": "string", "required": true }
                  ],
                  "access": { "write": "ownerId == $user.id" }
                }
              ]
            }
            """;

    private static final String WITHOUT_ACCESS_RULES = """
            {
              "namespace": "reg44",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Ticket",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "ownerId", "type": "string", "required": true }
                  ]
                }
              ]
            }
            """;

    private static CompiledModel compile(String json) throws Exception {
        Path path = Files.createTempFile("npdev-reg44-", ".json");
        Files.writeString(path, json);
        return new ModelCompiler().compile(new JsonModelParser().parse(path));
    }

    /** A resolver with {@code crud.kernelControlled} forced to {@code value} at application scope. */
    private static SettingResolver kernelControlled(boolean value) {
        return new SettingResolver(SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.CRUD_KERNEL_CONTROLLED.id(), value), "test")
                .build());
    }

    @Test
    void declaringAccessRulesWithKernelControlDisabledIsRejected() throws Exception {
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> UnenforceableAccessRuleCheck.verify(compile(WITH_ACCESS_RULES), kernelControlled(false)));

        // The message has to be actionable: which concept, which rule, and what to do about it.
        // A bare "invalid configuration" would send the author hunting through 13 template sites.
        assertTrue(rejected.getMessage().contains("REG-44"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("Ticket"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("access.write"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("crud.kernelControlled"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("config.json"), rejected.getMessage());
    }

    @Test
    void accessRulesWithKernelControlEnabledGenerateNormally() throws Exception {
        // The default, and by far the common case -- it must not become collateral damage.
        assertDoesNotThrow(() -> UnenforceableAccessRuleCheck.verify(compile(WITH_ACCESS_RULES), kernelControlled(true)));
    }

    @Test
    void disablingKernelControlWithNoAccessRulesIsStillAllowed() throws Exception {
        // Unmanaged CRUD remains a legitimate choice. The error is about the CONTRADICTION -- declaring
        // a rule and disabling the thing that enforces it -- not about the flag itself.
        assertDoesNotThrow(() -> UnenforceableAccessRuleCheck.verify(compile(WITHOUT_ACCESS_RULES), kernelControlled(false)));
    }

    @Test
    void aConceptScopedOptOutIsCaughtToo() throws Exception {
        // The reason the check resolves the setting PER CONCEPT rather than once for the app: the
        // setting is overridable at concept scope, so an app-level read would miss exactly the
        // targeted opt-out that is most likely to be deliberate -- and most likely to be forgotten.
        SettingResolver appOnTargetedOff = new SettingResolver(SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.CRUD_KERNEL_CONTROLLED.id(), true), "test app default")
                .layer(SettingScope.CONCEPT, "concept:Ticket",
                        Map.of(NpdevSettings.CRUD_KERNEL_CONTROLLED.id(), false), "test concept override")
                .build());

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> UnenforceableAccessRuleCheck.verify(compile(WITH_ACCESS_RULES), appOnTargetedOff));
        assertTrue(rejected.getMessage().contains("Ticket"), rejected.getMessage());
    }

    @Test
    void theFacadeRefusesToEmitAnythingForTheOffendingCombination() throws Exception {
        // End-to-end through the real facade: the check must run BEFORE any emitter, or a partially
        // generated app is left on disk for the author to run by mistake.
        Path out = Files.createTempDirectory("npdev-reg44-out-");
        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                kernelControlled(false));

        assertThrows(IllegalStateException.class,
                () -> facade.generate(compile(WITH_ACCESS_RULES), out, out.resolve("schema")));

        try (var entries = Files.walk(out)) {
            assertTrue(entries.filter(Files::isRegularFile).findAny().isEmpty(),
                    "nothing may be emitted when generation is refused");
        }
    }
}
