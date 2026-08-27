package com.finalexec.db.datamobility;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvRowFormatTest {

    private static List<String> roundTrip(String... values) throws IOException {
        StringWriter out = new StringWriter();
        CsvRowFormat.writeRow(out, Arrays.asList(values));
        BufferedReader reader = new BufferedReader(new StringReader(out.toString()));
        return CsvRowFormat.readRow(reader);
    }

    @Test
    void plainValuesRoundTrip() throws IOException {
        assertEquals(List.of("a", "b", "c"), roundTrip("a", "b", "c"));
    }

    @Test
    void nullIsDistinctFromEmptyString() throws IOException {
        List<String> values = new java.util.ArrayList<>();
        values.add(null);
        values.add("");
        values.add("x");
        StringWriter out = new StringWriter();
        CsvRowFormat.writeRow(out, values);
        BufferedReader reader = new BufferedReader(new StringReader(out.toString()));
        List<String> parsed = CsvRowFormat.readRow(reader);
        assertNull(parsed.get(0));
        assertEquals("", parsed.get(1));
        assertEquals("x", parsed.get(2));
    }

    @Test
    void commaQuoteAndNewlineInsideAValueRoundTrip() throws IOException {
        List<String> parsed = roundTrip("has,comma", "has\"quote", "has\nnewline", "has\r\ncrlf");
        assertEquals("has,comma", parsed.get(0));
        assertEquals("has\"quote", parsed.get(1));
        assertEquals("has\nnewline", parsed.get(2));
        assertEquals("has\r\ncrlf", parsed.get(3));
    }

    @Test
    void multipleRowsReadSequentiallyFromTheSameStream() throws IOException {
        StringWriter out = new StringWriter();
        CsvRowFormat.writeRow(out, List.of("h1", "h2"));
        CsvRowFormat.writeRow(out, List.of("r1c1", "r1,c2"));
        CsvRowFormat.writeRow(out, List.of("r2c1", "r2c2"));
        BufferedReader reader = new BufferedReader(new StringReader(out.toString()));
        assertEquals(List.of("h1", "h2"), CsvRowFormat.readRow(reader));
        assertEquals(List.of("r1c1", "r1,c2"), CsvRowFormat.readRow(reader));
        assertEquals(List.of("r2c1", "r2c2"), CsvRowFormat.readRow(reader));
        assertNull(CsvRowFormat.readRow(reader));
    }

    @Test
    void quotedFieldContainingEscapedQuotesRoundTrips() throws IOException {
        List<String> parsed = roundTrip("she said \"hi\"", "plain");
        assertEquals("she said \"hi\"", parsed.get(0));
        assertEquals("plain", parsed.get(1));
    }
}
