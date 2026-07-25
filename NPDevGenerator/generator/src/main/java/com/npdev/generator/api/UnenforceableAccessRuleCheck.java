package com.npdev.generator.api;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * REG-44: refuse to generate an app whose declared row-level access rules would never be enforced.
 *
 * <h2>The bug this closes</h2>
 *
 * <p>A concept can declare {@code access.read} / {@code access.write}, and the app can independently
 * set {@code crud.kernelControlled: false}. Both are valid on their own, the combination compiled
 * cleanly, and the resulting app silently enforced neither the declared {@code access.write} rule nor
 * any coarse CRUD permission check — 13 emission sites in {@code service-base.mustache} sit inside
 * {@code &#123;&#123;#kernelControlled&#125;&#125;}, covering READ, LIST, CREATE, UPDATE and DELETE,
 * plus mutation audit.</p>
 *
 * <p><b>What made it genuinely hard to notice</b>: {@code access.read} keeps working. Generated reads
 * go through {@code conceptGateway.read/list/query}, which is emitted unconditionally, so a developer
 * spot-checking "are my access rules live?" on a read path gets a reassuring yes — while every write
 * is unguarded.</p>
 *
 * <h2>Why this is an error and not a warning</h2>
 *
 * <p>Owner decision, 2026-07-25. A security rule the author wrote down and the platform silently does
 * not apply is the worst of both worlds: it produces documentation that lies. Refusing to generate
 * costs nothing real — {@code crud.kernelControlled} defaults to {@code true} and no model in this
 * repository sets it false — and the author has two clear ways forward, both named in the message.</p>
 *
 * <h2>Why it lives here and not in {@code SemanticValidator}</h2>
 *
 * <p>The validator sees only the model. {@code crud.kernelControlled} comes from {@code config.json}
 * via {@code ConfigSettingsReader}, so the contradiction is only visible where the compiled model and
 * the resolved settings meet — which is {@link GeneratorFacade}. The setting is resolved <b>per
 * concept</b> rather than once for the app, because it is overridable at concept scope
 * ({@code overrides: &#123; "concept:Order": &#123; "crud.kernelControlled": false &#125; &#125;}),
 * and an app-level check would miss exactly that targeted opt-out.</p>
 */
final class UnenforceableAccessRuleCheck {

    private UnenforceableAccessRuleCheck() {
    }

    static void verify(CompiledModel model, SettingResolver settingResolver) {
        if (model == null || settingResolver == null) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null) {
                continue;
            }
            List<String> declared = declaredAccessRules(concept);
            if (declared.isEmpty()) {
                continue;
            }
            boolean kernelControlled = settingResolver.value(
                    NpdevSettings.CRUD_KERNEL_CONTROLLED, SettingTarget.concept(concept.getName()));
            if (!kernelControlled) {
                offenders.add("  - concept '" + concept.getName() + "' declares " + String.join(" and ", declared));
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "REG-44: this model declares row-level access rules that the generated app would never enforce.\n"
                        + String.join("\n", offenders) + "\n"
                        + "  ...but 'crud.kernelControlled' resolves to false for them.\n"
                        + "\n"
                        + "With crud.kernelControlled=false the generator omits every authorization call from the\n"
                        + "generated service: the coarse concept permission checks (READ/LIST/CREATE/UPDATE/DELETE),\n"
                        + "the row-level access.write gate, and mutation audit. Only access.read keeps working, because\n"
                        + "generated reads go through the concept gateway either way -- which is exactly what makes this\n"
                        + "combination look harmless when you spot-check it.\n"
                        + "\n"
                        + "Choose one:\n"
                        + "  (a) remove the access rules from the concept(s) above, if unmanaged CRUD is what you want; or\n"
                        + "  (b) set crud.kernelControlled back to true (the platform default) for them, in config.json\n"
                        + "      -- either in \"defaults\" or in the matching \"overrides\" entry.");
    }

    private static List<String> declaredAccessRules(CompiledConcept concept) {
        List<String> declared = new ArrayList<>();
        if (concept.getAccess() == null) {
            return declared;
        }
        if (hasText(concept.getAccess().getRead())) {
            declared.add("access.read");
        }
        if (hasText(concept.getAccess().getWrite())) {
            declared.add("access.write");
        }
        return declared;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
