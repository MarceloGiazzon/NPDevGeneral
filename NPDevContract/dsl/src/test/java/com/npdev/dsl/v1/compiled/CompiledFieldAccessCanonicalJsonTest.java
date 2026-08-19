package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R5.5 (roadmap Wave 1, 2026-08-19): {@code CompiledField.access} -- the field-level {read, write}
 * authorization rule -- must survive the compiled-model canonical JSON round trip, exactly the
 * hazard {@link CompiledFieldPickerCanonicalJsonTest} documents for {@code picker} (HARDEN-OBJSTORE:
 * a writer-only field silently vanishes on read, defeating the feature in every generated app while
 * a writer-only unit test stays green).
 */
class CompiledFieldAccessCanonicalJsonTest {

    @Test
    void fieldAccessSurvivesCanonicalRoundTrip() throws Exception {
        CompiledField salary = new CompiledField(
                "salary", "int", "Integer", false, false, false,
                List.of(), null, null, null, null, List.of(), null,
                null, null, null, false, null,
                new CompiledFieldAccess("$user.actorId == 'manager-1'", "$user.actorId == 'manager-1'")
        );
        CompiledConcept payroll = new CompiledConcept("Payroll", "Payroll", "payrolls", List.of(salary));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0", Map.of(payroll.getName(), payroll));

        String json = CompiledModelCanonicalJson.toJson(model);
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        CompiledField backField = back.getConcepts().iterator().next().getFields().get(0);
        assertEquals("salary", backField.getName());
        CompiledFieldAccess access = backField.getAccess();
        assertNotNull(access, "field.access must survive the canonical JSON round trip, not silently vanish on read");
        assertEquals("$user.actorId == 'manager-1'", access.getRead());
        assertEquals("$user.actorId == 'manager-1'", access.getWrite());
    }

    @Test
    void fieldWithNoAccessRoundTripsToANullAccess() throws Exception {
        CompiledField plain = new CompiledField("name", "string", "String", false, true, false);
        CompiledConcept payroll = new CompiledConcept("Payroll", "Payroll", "payrolls", List.of(plain));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0", Map.of(payroll.getName(), payroll));

        String json = CompiledModelCanonicalJson.toJson(model);
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        assertNull(back.getConcepts().iterator().next().getFields().get(0).getAccess());
    }
}
