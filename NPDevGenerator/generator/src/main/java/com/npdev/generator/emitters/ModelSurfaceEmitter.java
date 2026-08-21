package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R10.2: emits a read-only surface over the FULL compiled model --
 * {@code static/model-surface.html} -- whose content comes straight from
 * {@link CompiledModelCanonicalJson#toJson(CompiledModel)}, the same canonical writer
 * {@code MetadataManifestAssetEmitter} and {@code RuntimeApiEmitter}'s
 * {@code compiled-model.json} already use.
 *
 * <p><b>Enumerated, not named.</b> This class does not ask the model for any one part of itself
 * by name -- it hands the whole canonical object graph to the template, and the template's script
 * walks whatever top-level keys the JSON actually has. A part of the model this class has never
 * heard of still shows up on the page, because nothing here (or in {@code model-surface.mustache})
 * branches on which part it is looking at. That is the property this item exists to prove: the
 * frozen bundle (removed in R10.3) hardcoded a handful of panes and silently drops
 * anything else, and a platform feature landing today should not have to wait for that bundle's
 * next rebuild to become visible anywhere.
 *
 * <p><b>Embedded, not fetched</b> -- the same choice {@link InfoPageEmitter} made (see its javadoc,
 * "D2-b"): the compiled model is already fully known at generation time, so the page carries its
 * own copy inline rather than calling back into the running app for data that cannot have changed
 * since. That also means the page needs no new controller route (this item's owned surface is an
 * emitter and a template, nothing under {@code NPDevRuntimeHost}) and keeps working when opened
 * from {@code file://}, where {@code fetch()} is blocked by every browser.
 *
 * <p><b>Read-only.</b> The page issues no request that mutates anything. R10.1 removed this
 * platform's only draft write-back endpoints; nothing here reopens that door.
 */
public final class ModelSurfaceEmitter extends AbstractEmitter {

    public ModelSurfaceEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        String modelJson = CompiledModelCanonicalJson.toJson(model);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        // Same escape InfoPageEmitter applies to its embedded JSON: stops a value that literally
        // contains "</script>" from closing the embedding tag early.
        ctx.put("modelJson", modelJson.replace("</", "<\\/"));
        writer.writeRelative("src/main/resources/static/model-surface.html",
                templates.render("model-surface.mustache", ctx));
    }
}
