import java.util.List;
import java.util.Map;

public final class CreateUsersProcedure {
    public Map<String, Object> execute(NPDevProcedureContext ctx) {
        List<Map<String, Object>> users = List.of(
            Map.of(
                "id", "00000000-0000-0000-0000-000000000001",
                "name", "Ana Silva",
                "email", "ana@example.test",
                "active", true
            ),
            Map.of(
                "id", "00000000-0000-0000-0000-000000000002",
                "name", "Bruno Costa",
                "email", "bruno@example.test",
                "active", true
            ),
            Map.of(
                "id", "00000000-0000-0000-0000-000000000003",
                "name", "Carla Rocha",
                "email", "carla@example.test",
                "active", true
            )
        );

        List<Map<String, Object>> savedUsers = ctx.saveMany("User", users);

        return Map.of(
            "createdCount", savedUsers.size(),
            "users", savedUsers
        );
    }
}
