import java.util.List;
import java.util.Map;

public final class Greeter {
    public Map<String, Object> execute(NPDevProcedureContext ctx) {
        List<Map<String, Object>> saved = ctx.saveMany("Thing", List.of(Map.of("name", "hello from " + ctx.tenantId())));
        return Map.of("saved", saved.size());
    }
}
