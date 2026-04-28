package com.npdev.generator.migration;

import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSchemaFromCompiledModelTest {

    @Test
    void usesCompiledTableNamesForMigrationPlanSchema() {
        CompiledEntity examRoom = new CompiledEntity(
                "ExamRoom",
                "ExamRoom",
                "examrooms",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("code", "string", "String", false, true, true),
                        new CompiledField("name", "string", "String", false, false, false),
                        new CompiledField("openedAt", "datetime", "java.time.Instant", false, false, false)
                )
        );

        CompiledEntity appointment = new CompiledEntity(
                "Appointment",
                "Appointment",
                "appointments",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("roomId", "reference", "java.util.UUID", false, true, false)
                )
        );

        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(examRoom.getName(), examRoom);
        entities.put(appointment.getName(), appointment);
        CompiledModel model = new CompiledModel("default", "v1", entities);

        StorageSchemaSnapshot snapshot = new StorageSchemaFromCompiledModel().from(model);
        Set<String> tableNames = snapshot.tables().stream()
                .map(StorageTableSchema::name)
                .collect(Collectors.toSet());

        assertTrue(tableNames.contains("examrooms"));
        assertTrue(tableNames.contains("appointments"));
        assertEquals(Set.of("examrooms", "appointments"), tableNames);
        StorageColumnSchema openedAt = snapshot.tables().stream()
                .filter(table -> table.name().equals("examrooms"))
                .flatMap(table -> table.columns().stream())
                .filter(column -> column.name().equals("opened_at"))
                .findFirst()
                .orElseThrow();
        assertEquals("TIMESTAMP WITH TIME ZONE", openedAt.sqlType());
    }
}
