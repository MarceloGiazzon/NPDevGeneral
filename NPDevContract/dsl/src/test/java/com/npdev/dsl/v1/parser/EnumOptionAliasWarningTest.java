package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENF-1 (D5): an enum option setting both keys of an alias pair warns that the alias is ignored. */
class EnumOptionAliasWarningTest {

    private static ModelAst parse(String options) throws Exception {
        String model = "{\"dslVersion\":\"1.0.0\",\"namespace\":\"alias.test\",\"version\":\"1.0\","
                + "\"concepts\":[{\"name\":\"Thing\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
                + "{\"name\":\"state\",\"type\":\"enum\",\"enumValues\":" + options + "}]}]}";
        return new JsonModelParser().parse(new ObjectMapper().readTree(model));
    }

    @Test
    void bothKeysOfAPairWarnThatTheAliasIsIgnored() throws Exception {
        List<String> warnings = parse("[{\"value\":\"Draft\",\"label\":\"Draft\",\"displayLabel\":\"In draft\","
                + "\"description\":\"d\",\"help\":\"h\",\"icon\":\"edit\",\"iconHint\":\"pencil\"}]").getParserWarnings();

        assertEquals(3, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("(Draft) sets both 'label' and its alias 'displayLabel'"), warnings.get(0));
        assertTrue(warnings.get(0).contains("enumValues[0]"), warnings.get(0));
    }

    @Test
    void anAliasAloneOrPlainStringsDoNotWarn() throws Exception {
        assertTrue(parse("[{\"value\":\"Retired\",\"displayLabel\":\"Withdrawn\",\"help\":\"gone\"}, \"Live\"]")
                .getParserWarnings().isEmpty());
    }
}
