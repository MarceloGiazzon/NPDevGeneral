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
 * top-level shape, one scaffolding action ("Add concept", a name plus a couple of typed fields),
 * and export back to {@code model.json} in the SAME folder. No starter templates, no other
 * scaffolding action (field/flow/panel/invariant/state/transition), no richer editing UI -- see the
 * ledger item for the full deferred list.
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
