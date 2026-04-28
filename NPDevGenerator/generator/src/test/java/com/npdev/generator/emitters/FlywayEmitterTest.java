package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayEmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsPortableRepeatableSchemaForReleaseMatrixDatabases() throws Exception {
        CompiledConcept appointment = new CompiledConcept(
                "Appointment",
                "Appointment",
                "appointments",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("email", "string", "String", false, true, true),
                        new CompiledField("checkInTime", "datetime", "java.time.Instant", false, false, false)
                )
        );

        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(appointment.getName(), appointment);
        CompiledModel model = new CompiledModel("default", "v1", concepts);

        Path file = new FlywayEmitter().emitRepeatableSchema(model, tempDir);
        String sql = Files.readString(file);

        assertTrue(sql.contains("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS check_in_time TIMESTAMP WITH TIME ZONE;"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS ux_appointments_email ON appointments (email);"));
        assertFalse(sql.contains("TIMESTAMPTZ"));
        assertFalse(sql.contains("lower("));
    }
}
