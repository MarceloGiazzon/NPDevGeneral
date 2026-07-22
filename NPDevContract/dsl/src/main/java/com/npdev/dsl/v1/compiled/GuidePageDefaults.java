package com.npdev.dsl.v1.compiled;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three platform-default GuidePages that always exist, even for an app that declares none:
 * {@code Default} (top bar + left nav), {@code Minimal} (top bar only) and {@code None} (no chrome
 * at all -- the section owns the whole viewport, e.g. a login screen). An app-declared GuidePage
 * with one of these names overrides the built-in of the same name rather than duplicating it, so
 * {@link #withBuiltins} always returns exactly one GuidePage per name.
 */
public final class GuidePageDefaults {

    public static final String DEFAULT_NAME = "Default";
    public static final String MINIMAL_NAME = "Minimal";
    public static final String NONE_NAME = "None";

    public static final List<String> BUILTIN_NAMES = List.of(DEFAULT_NAME, MINIMAL_NAME, NONE_NAME);

    private GuidePageDefaults() {
    }

    private static CompiledGuidePage builtin(String name, boolean top, boolean left, boolean right) {
        return new CompiledGuidePage(
                name,
                false,
                new CompiledGuidePageRegions(
                        top,
                        new CompiledGuidePageRegion(left, left, false, 0),
                        new CompiledGuidePageRegion(right, right, true, 280)
                ),
                new CompiledGuidePageTheme("light", "", "comfortable", "", ""),
                List.of()
        );
    }

    /**
     * Merges the app's declared GuidePages over the three built-ins (declared entries with a
     * built-in name replace it in place; every other declared entry is appended), then resolves
     * the effective default: the declared entry with {@code isDefault() == true}, if any, else the
     * (possibly overridden) {@code Default} entry.
     */
    public static Result withBuiltins(List<CompiledGuidePage> declared) {
        Map<String, CompiledGuidePage> byName = new LinkedHashMap<>();
        byName.put(DEFAULT_NAME, builtin(DEFAULT_NAME, true, true, true));
        byName.put(MINIMAL_NAME, builtin(MINIMAL_NAME, true, false, false));
        byName.put(NONE_NAME, builtin(NONE_NAME, false, false, false));

        String defaultName = DEFAULT_NAME;
        if (declared != null) {
            for (CompiledGuidePage page : declared) {
                if (page == null || page.name() == null || page.name().isBlank()) {
                    continue;
                }
                byName.put(page.name(), page);
                if (page.isDefault()) {
                    defaultName = page.name();
                }
            }
        }
        return new Result(List.copyOf(byName.values()), defaultName);
    }

    public record Result(List<CompiledGuidePage> guidePages, String defaultGuidePage) {
    }
}
