package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Move 6 Move A: the closed catalogue of platform-owned UI string ids and their English defaults.
 * This is the fix for the fault line named in {@code docs/MOVE6_TYPED_SURFACE_PLAN.md} §2 -- every
 * generated app's Aggregate Workbench page mixed English platform text with Portuguese literal
 * fallbacks baked into the compiler/template (e.g. "Save" beside "Adicionar"). An app overrides any
 * subset of these ids via {@code model.settings.strings}; every id not overridden keeps this
 * English default, so a model that declares no {@code settings} block renders coherent English
 * instead of today's mix. Referenced by both {@link CompiledSettings} (merges app overrides on top)
 * and {@code AutoPanelExpander} (bakes the resolved value into compiled panelAction/picker labels).
 */
public final class PlatformStrings {
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("workbench.header", "Header");
        d.put("workbench.readOnly", "Read-only");
        d.put("workbench.editingDisabled", "Editing is disabled in this state.");
        d.put("workbench.revert", "revert");
        d.put("workbench.revertTitle", "Discard changes in this region");
        d.put("workbench.saved", "Saved");
        d.put("state.phase", "Phase");
        d.put("action.select", "Select");
        d.put("action.add", "Add");
        d.put("action.cancel", "Cancel");
        d.put("action.save", "Save");
        d.put("action.delete", "Delete");
        d.put("action.new", "New");
        // REG-146: Map.copyOf(d) here (rather than Collections.unmodifiableMap) is what actually
        // made this nondeterministic -- JDK's ImmutableCollections implementation deliberately
        // randomizes iteration order per JVM run (a JEP 269 hash-flood mitigation), discarding the
        // LinkedHashMap insertion order built above despite reading as ordered at this call site.
        // CompiledSettings.getStrings() (a LinkedHashMap seeded from these DEFAULTS, app overrides
        // merged on top) inherited that randomized order, which BusinessUiEmitter then serializes
        // verbatim into every generated app's workbench-page.html.mustache i18n blob --
        // byte-different output between two back-to-back generations of the identical model.
        // Collections.unmodifiableMap preserves the LinkedHashMap's real insertion order while
        // staying just as immutable to callers.
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private PlatformStrings() {
    }
}
