package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EDIT-12: emits {@code static/model-authoring.html}, a MINIMAL client-side replacement for the
 * frozen {@code static-react/assets/AuthoringApp.js} bundle's scaffolding + local-folder
 * import/export capability -- the one chunk of that bundle {@code ledger/items/EDIT-12.yml} found
 * to have no replacement anywhere else (its Model/Rule editor siblings are already dead: R10.1
 * deleted the draft write-back endpoints they called).
 *
 * <p><b>What this ships, on purpose narrower than the frozen bundle:</b> import an arbitrary
 * {@code model.json} from a local folder via {@code window.showDirectoryPicker}, render its
 * top-level shape, scaffold into it, and export back to {@code model.json} in the SAME folder.
 * As of the second slice that is BOTH starter templates the frozen bundle offered (Business Record,
 * Approval Workflow) and all seven of its scaffolding actions -- add concept, field, flow, panel,
 * invariant, state and transition.
 *
 * <p><b>Scaffolding emits shapes that compile, not empty shells.</b> Each action is checked against
 * what the DSL actually requires, so the page cannot hand back a model the generator would reject:
 * a new concept always gets its {@code id:uuid} field; "add state" creates the concept's
 * {@code lifecycle} AND the {@code status} field its {@code statusField} names, because a
 * lifecycle pointing at a field the concept does not declare will not compile; "add transition"
 * offers only states that concept really declares, and refuses a self-transition or a duplicate;
 * "add flow" emits an {@code input} bound to the chosen concept plus a {@code return} step rather
 * than a step-less shell; "add panel" derives a route from the name when none is given and refuses
 * a route another panel already holds. Duplicate names are refused everywhere rather than
 * overwritten, and a starter template MERGES -- skipping anything already present -- so applying
 * one to a populated model cannot destroy it. The templates are built from the very same helpers
 * the manual actions use, so a template cannot produce a shape the actions could not.
 *
 * <p><b>Editing what exists, not only adding.</b> Rename/delete a concept, rename/retype/remove a
 * field, and remove a lifecycle state. Renaming is the dangerous one and is treated as such: a
 * concept or field that was present in the model AS IMPORTED gets a {@code renamedFrom} stamp, so a
 * regeneration's schema-lifecycle classifies it as a RENAME rather than an unrelated drop-and-create
 * that would destroy the table's or column's data -- while one created during the session is
 * deliberately NOT stamped, because there is nothing on disk to rename. Across repeated renames the
 * stamp keeps naming the original on-disk name, and a field's stamp is looked up by its concept's
 * on-disk name so renaming the concept first does not silently lose it. Every reference to a renamed
 * concept is repointed in the same step by a generic walk over every string under a
 * concept-naming key, so a DSL section this page has never heard of is still updated. Deletes and
 * removals are refused, naming the blocker, when something still points at the target, when it is
 * the concept's id field, or when it backs a lifecycle. Free-text expressions that mention a renamed
 * field are reported, never silently rewritten.
 *
 * <p>Still deferred: reference-typed and enum fields in the add-field forms, multi-step flow
 * authoring, and editing a flow's or panel's internals once created -- see the ledger item.
 *
 * <p><b>Same shape as {@link ModelSurfaceEmitter}, on purpose.</b> The structural view reuses that
 * emitter's generic walk (array of objects -> collapsible entries, object -> key/value table,
 * scalar -> text) so a DSL section this page has never heard of still renders once imported, the
 * same "enumerated, not named" property R10.2 established. This is the "R10.2's surface extended
 * from read-only to model-authoring" path {@code EDIT-12.yml} names as the owner-picked
 * unblocking route for R10.3 -- landed as a sibling emitter/template pair rather than edited into
 * {@code model-surface.mustache} itself, so {@code ModelSurfaceEmitterTest
 * .emitterAndTemplateNeverNameAModelSectionLiterally}'s forbidden-token guard (which exists to keep
 * THAT page's read-only compiled-model view free of hardcoded section names) stays scoped to that
 * page and is not accidentally tripped by this page's necessarily-named "concepts"/"fields"
 * scaffolding action.
 *
 * <p><b>Embedded escape hatch aside, this page fetches and posts nothing.</b> It never calls back
 * into this app's own server: no {@code fetch()}/XHR anywhere in its script, and critically no
 * {@code POST} of an edited model to any endpoint. R10.1 deleted this platform's only server-side
 * draft write-back path specifically to stop a running app's server from silently diverging from
 * its own {@code model.json} source of truth; this page keeps that door shut by construction --
 * every read and write here goes straight to the user's OWN chosen local folder via the browser's
 * File System Access API, never through this app's server at all. See
 * {@code ModelAuthoringEmitterTest} for the mechanical proof.
 *
 * <p>Unconditional, like {@link ModelSurfaceEmitter} and {@link InfoPageEmitter}: it walks whatever
 * model.json a user later chooses to import, not this app's own business-UI panels, so it has no
 * dependency on {@code UI_GENERATE_BUSINESS_UI}.
 */
public final class ModelAuthoringEmitter extends AbstractEmitter {

    public ModelAuthoringEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        writer.writeRelative("src/main/resources/static/model-authoring.html",
                templates.render("model-authoring.mustache", ctx));
    }
}
