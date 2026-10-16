package com.finalexec.config;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ENG-3: everything {@code MetadataHotSwapController#modelReload} must check BEFORE it calls
 * {@link ModelHolder#swap}. Lives in runtimehost-core (not the per-app template) so it is unit-testable
 * with a plain JUnit test -- the controller only maps {@link Outcome} to HTTP.
 *
 * <p>Why each refusal exists:
 * <ul>
 *   <li>{@code MODEL_INVALID}: GeneratorMain refuses a model SemanticValidator rejects
 *       (GeneratorMain.java:85-97); the reload used to parse+compile only, so an invalid model could be
 *       swapped into a running app.</li>
 *   <li>{@code RELOAD_REMOVES_CONCEPTS}: the boot model was produced by GeneratorMain, which ALSO composes
 *       config.json-driven packs (internal.tables -> identity/workspace/tracing, packs.included) that a
 *       raw model.json does not contain (GeneratorMain.java:101-131). Swapping such a model silently
 *       drops e.g. identity::User, which turns off IdentityAwareContextResolver's revoked-token check.
 *       Removing a concept is also a DROP_TABLE (MANUAL_REVIEW) change that must go through a rebuild.</li>
 * </ul>
 */
public final class ModelReloadPreflight {

    private ModelReloadPreflight() {
    }

    public record Outcome(CompiledModel model, String code, String message, List<String> details) {
        public boolean ok() {
            return model != null;
        }

        static Outcome accepted(CompiledModel model, List<String> warnings) {
            return new Outcome(model, null, null, List.copyOf(warnings));
        }

        static Outcome refused(String code, String message, List<String> details) {
            return new Outcome(null, code, message, List.copyOf(details));
        }
    }

    /**
     * @param newConceptsServable false when the app has a physical DataSource but
     *        npdev.runtime.hotswap.new-concept-provisioning-enabled is off -- then a brand-new concept would
     *        be swapped into ModelHolder with no table behind it (every CRUD call a raw SQL error), while
     *        `npdev monitor studio-apply` reports APPLIED_LIVE. InMemory apps (no DataSource) pass true:
     *        InMemoryConceptStore picks new concepts up through its own reload listener.
     */
    public static Outcome prepare(Path modelFile, CompiledModel liveModel, boolean newConceptsServable)
            throws java.io.IOException {
        ModelAst ast = new JsonModelParser().parse(modelFile);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        if (validation.hasErrors()) {
            return Outcome.refused("MODEL_INVALID",
                    "the model failed semantic validation; nothing was swapped", validation.getErrors());
        }
        CompiledModel candidate = new ModelCompiler().compile(ast);

        List<String> removed = new ArrayList<>();
        for (CompiledConcept concept : liveModel.getConcepts()) {
            if (candidate.findConcept(concept.getName()).isEmpty()) {
                removed.add(concept.getOrigin() == null
                        ? concept.getName()
                        : concept.getName() + " (from pack " + concept.getOrigin().packId()
                                + " -- composed at generation time from config.json; live reload cannot recompose it)");
            }
        }
        if (!removed.isEmpty()) {
            return Outcome.refused("RELOAD_REMOVES_CONCEPTS",
                    "the reloaded model lacks concepts the running app has; removing a concept needs a rebuild",
                    removed);
        }
        if (!newConceptsServable) {
            List<String> added = new ArrayList<>();
            for (CompiledConcept concept : candidate.getConcepts()) {
                if (liveModel.findConcept(concept.getName()).isEmpty()) {
                    added.add(concept.getName());
                }
            }
            if (!added.isEmpty()) {
                return Outcome.refused("NEW_CONCEPT_PROVISIONING_DISABLED",
                        "the model adds concepts but this app cannot create their tables live "
                                + "(npdev.runtime.hotswap.new-concept-provisioning-enabled=false); rebuild, or enable it",
                        added);
            }
        }
        return Outcome.accepted(candidate, validation.getWarnings());
    }
}
