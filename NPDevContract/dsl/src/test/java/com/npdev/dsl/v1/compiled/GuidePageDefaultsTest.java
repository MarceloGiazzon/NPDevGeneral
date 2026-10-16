package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GuidePageDefaultsTest {

    @Test
    void builtinPagesDoNotPinThemeModeOrDensity() {
        GuidePageDefaults.Result result = GuidePageDefaults.withBuiltins(List.of());

        assertEquals(List.of("Default", "Minimal", "None"),
                result.guidePages().stream().map(CompiledGuidePage::name).toList());
        for (CompiledGuidePage page : result.guidePages()) {
            assertNull(page.theme().mode(), page.name() + " must not pin a theme mode");
            assertNull(page.theme().density(), page.name() + " must not pin a density");
        }
    }

    @Test
    void declaredPageReplacesBuiltinAndKeepsItsExplicitMode() {
        CompiledGuidePage declared = new CompiledGuidePage(
                "Default",
                true,
                new CompiledGuidePageRegions(true,
                        new CompiledGuidePageRegion(true, true, false, 0),
                        new CompiledGuidePageRegion(false, false, true, 280)),
                new CompiledGuidePageTheme("dark", "", "compact", "", ""),
                List.of());

        GuidePageDefaults.Result result = GuidePageDefaults.withBuiltins(List.of(declared));

        CompiledGuidePage page = result.guidePages().stream()
                .filter(p -> p.name().equals("Default")).findFirst().orElseThrow();
        assertEquals("dark", page.theme().mode());
        assertEquals("compact", page.theme().density());
        assertEquals(3, result.guidePages().size());
    }
}
