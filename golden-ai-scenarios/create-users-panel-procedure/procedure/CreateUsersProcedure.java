import java.util.List;
import java.util.Map;

public final class CreateUsersProcedure {
    public Map<String, Object> execute(NPDevProcedureContext ctx) {
        List<Map<String, Object>> users = List.of(
            Map.of(
                "id", "u-001",
                "name", "Ana Silva",
                "email", "ana@example.test",
                "active", true
            ),
            Map.of(
                "id", "u-002",
                "name", "Bruno Costa",
                "email", "bruno@example.test",
                "active", true
            ),
            Map.of(
                "id", "u-003",
                "name", "Carla Rocha",
                "email", "carla@example.test",
                "active", true
            )
        );

        System.out.print("Creating 3 User records from trusted Java procedure code.");
        List<Map<String, Object>> savedUsers = ctx.saveMany("User", users);

        return Map.of(
            "createdCount", savedUsers.size(),
            "users", savedUsers
        );
    }
}
