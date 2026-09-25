package com.npdev.dsl.v1.cli;

import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;

import java.util.List;
import java.util.Locale;

/**
 * Wave 2.2: entry point {@code npdev widgets} shells out to, the same shape as
 * {@code SqlDialects.main}'s {@code npdev doctor}/{@code capabilities} entry point -- the CLI reads
 * this rather than carrying its own copy of the widget list in Python, so the two can never
 * disagree. Reads {@link FieldWidgetDefaults#catalogue()} directly (no model file needed: the
 * catalogue describes the widget SYSTEM, not any one app), same data the generator's
 * {@code WidgetCatalogueEmitter} writes into every app's {@code static/widget-catalog.json}.
 *
 * <p>JSON is hand-built (like {@code SqlDialects.capabilityMatrixJson}), not via Jackson: this runs
 * off the plain {@code runtimehost-libs} classpath the same way {@code capabilities --json} does,
 * which stages the dsl jar but not a standalone jackson-databind jar.
 */
public final class WidgetCatalogueMain {

    private WidgetCatalogueMain() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH);

        boolean json = false;
        String typeFilter = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--json".equals(arg)) {
                json = true;
            } else if ("--type".equals(arg) && i + 1 < args.length) {
                typeFilter = args[++i];
            } else if (arg.startsWith("--type=")) {
                typeFilter = arg.substring("--type=".length());
            }
        }

        List<FieldWidgetDefaults.WidgetCatalogueEntry> entries = FieldWidgetDefaults.catalogue();
        if (typeFilter != null) {
            String wanted = typeFilter.trim().toLowerCase(Locale.ROOT);
            entries = entries.stream()
                    .filter(e -> e.compatibleTypes().stream()
                            .anyMatch(t -> t.toLowerCase(Locale.ROOT).startsWith(wanted)))
                    .toList();
        }

        System.out.println(json ? toJson(entries) : toText(entries, typeFilter));
        System.exit(0);
    }

    private static String toText(List<FieldWidgetDefaults.WidgetCatalogueEntry> entries, String typeFilter) {
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        out.append("npdev widgets").append(typeFilter != null ? " --type " + typeFilter : "")
                .append(" -- ").append(entries.size()).append(" widget(s)").append(nl);
        out.append("=".repeat(60)).append(nl);
        for (FieldWidgetDefaults.WidgetCatalogueEntry entry : entries) {
            out.append(nl).append(entry.name()).append(nl);
            out.append("  compatible types: ").append(String.join(", ", entry.compatibleTypes())).append(nl);
            if (!entry.isDefaultFor().isEmpty()) {
                out.append("  default for:      ").append(String.join(", ", entry.isDefaultFor())).append(nl);
            }
            out.append("  ").append(entry.description()).append(nl);
        }
        return out.toString();
    }

    private static String toJson(List<FieldWidgetDefaults.WidgetCatalogueEntry> entries) {
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        out.append("[").append(nl);
        for (int i = 0; i < entries.size(); i++) {
            FieldWidgetDefaults.WidgetCatalogueEntry entry = entries.get(i);
            out.append("  {").append(nl);
            out.append("    \"name\": ").append(jsonString(entry.name())).append(",").append(nl);
            out.append("    \"compatibleTypes\": ").append(jsonArray(entry.compatibleTypes())).append(",").append(nl);
            out.append("    \"isDefaultFor\": ").append(jsonArray(entry.isDefaultFor())).append(",").append(nl);
            out.append("    \"description\": ").append(jsonString(entry.description())).append(nl);
            out.append("  }").append(i == entries.size() - 1 ? "" : ",").append(nl);
        }
        out.append("]");
        return out.toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            out.append(jsonString(values.get(i))).append(i == values.size() - 1 ? "" : ", ");
        }
        return out.append("]").toString();
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append("\"").toString();
    }
}
