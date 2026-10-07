package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayLabelsTest {

    @Test
    void screamingSnakeEnumValuesBecomeSentenceCase() {
        assertEquals("Glossy", DisplayLabels.humanize("GLOSSY"));
        assertEquals("Hex offset", DisplayLabels.humanize("HEX_OFFSET"));
        assertEquals("In progress", DisplayLabels.humanize("IN-PROGRESS"));
    }

    @Test
    void camelCaseKeepsItsWordCapitalsAndAcronyms() {
        assertEquals("Dominant Color", DisplayLabels.humanize("dominantColor"));
        assertEquals("Checked In", DisplayLabels.humanize("CheckedIn"));
        assertEquals("URL Path", DisplayLabels.humanize("URLPath"));
        assertEquals("User ID", DisplayLabels.humanize("userID"));
        assertEquals("Line2 Total", DisplayLabels.humanize("line2Total"));
        assertEquals("Snake case", DisplayLabels.humanize("snake_case"));
        assertEquals("", DisplayLabels.humanize("  "));
    }
}
