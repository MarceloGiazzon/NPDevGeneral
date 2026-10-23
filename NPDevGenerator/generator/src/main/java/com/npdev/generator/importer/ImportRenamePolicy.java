package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * IMP-GAPS-1: the field rename policy of {@code npdev import db}. A legacy schema names its columns after the
 * table ({@code ArmCod, ArmNome, EntCod} in table {@code Armazens}); a hand-built model says {@code id, nome,
 * entidadeId}. {@link #plan} proposes the readable name per column, once per table, from four rules:
 *
 * <ol>
 *   <li><b>primaryKey</b> -- a single-column primary key is named {@code primaryKey.name} ({@code id});</li>
 *   <li><b>foreignKey</b> -- a table with exactly ONE single-column foreign key to a given (other) imported table
 *       names it {@code foreignKey.pattern} with the target concept in singular camelCase ({@code entidadeId});
 *       several keys to the same target, and a key to the table itself, only get the prefix stripped, because
 *       the target alone cannot tell them apart;</li>
 *   <li><b>prefix</b> -- the first word (camel or underscore boundary) shared by at least {@code minShare} of the
 *       table's non-foreign-key columns, and by at least {@code minColumns} of them, {@code minLength..maxLength}
 *       letters long and (with {@code mustAbbreviateTable}) an in-order abbreviation of the table name starting
 *       at its first letter, is stripped ({@code ArmNome -> nome});</li>
 *   <li>a proposal is DECLINED, and the legacy camelCase name kept, when the result is empty ({@code empty}),
 *       starts with a digit ({@code digit}), is a reserved word ({@code reserved}) or collides, ignoring case,
 *       with another field of the same table ({@code collision}).</li>
 * </ol>
 *
 * <p>Pure: no I/O besides loading the policy, nothing guessed from data. A column no rule touches is absent
 * from the plan. The policy itself is data ({@code npdev/importer/rename-policy.json} on the classpath, or a
 * file of the user's own); never the DSL {@code renamedFrom} key, which renames LIVE columns.
 */
public final class ImportRenamePolicy {

    public static final String SCHEMA_VERSION = "npdev-import-rename-policy.v1";
    static final String DEFAULT_RESOURCE = "/npdev/importer/rename-policy.json";

    /** One suffix rule for the singular of a target concept name; first match wins. */
    record SingularRule(String suffix, String replace, int minLength) {
    }

    /** The policy, loaded from JSON. {@link #DISABLED} keeps every legacy name. */
    public record Policy(String source,
                         boolean prefixEnabled, double minShare, int minColumns, int minLength, int maxLength,
                         boolean mustAbbreviateTable,
                         boolean foreignKeyEnabled, String foreignKeyPattern, List<SingularRule> singular,
                         boolean primaryKeyEnabled, String primaryKeyName,
                         Set<String> reservedWords) {
        boolean anyEnabled() {
            return prefixEnabled || foreignKeyEnabled || primaryKeyEnabled;
        }
    }

    /** One planned name: {@code field} is the name to use; it equals {@code legacyField} when {@code declined()}. */
    public record Rename(String column, String legacyField, String field, String rule) {
        public boolean applied() {
            return !field.equals(legacyField);
        }
    }

    public static final Policy DISABLED = new Policy("disabled", false, 1, Integer.MAX_VALUE, 0, 0, false,
            false, "{target}Id", List.of(), false, "id", Set.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImportRenamePolicy() {
    }

    // ---------------------------------------------------------------- loading

    /** The policy shipped on the classpath (so the CLI's npdev-ai-tools.jar has it). */
    public static Policy defaults() {
        try (InputStream in = ImportRenamePolicy.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("rename policy resource missing: " + DEFAULT_RESOURCE);
            }
            return fromJson(MAPPER.readTree(in), "default");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Policy load(Path file) throws IOException {
        return fromJson(MAPPER.readTree(Files.readString(file)), file.toString());
    }

    static Policy fromJson(JsonNode json, String source) {
        String version = json.path("schemaVersion").asText("");
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("rename policy " + source + ": schemaVersion must be " + SCHEMA_VERSION
                    + ", got '" + version + "'");
        }
        JsonNode prefix = json.path("fieldPrefix");
        JsonNode fk = json.path("foreignKey");
        JsonNode pk = json.path("primaryKey");
        List<SingularRule> singular = new ArrayList<>();
        for (JsonNode rule : fk.path("singular")) {
            singular.add(new SingularRule(rule.path("suffix").asText(""), rule.path("replace").asText(""),
                    rule.path("minLength").asInt(0)));
        }
        Set<String> reserved = new HashSet<>();
        json.path("reservedWords").forEach(w -> reserved.add(w.asText().toLowerCase(Locale.ROOT)));
        String pattern = fk.path("pattern").asText("{target}Id");
        if (!pattern.contains("{target}")) {
            throw new IllegalArgumentException("rename policy " + source + ": foreignKey.pattern must contain {target}");
        }
        return new Policy(source,
                prefix.path("enabled").asBoolean(false), prefix.path("minShare").asDouble(0.6),
                prefix.path("minColumns").asInt(3), prefix.path("minLength").asInt(2), prefix.path("maxLength").asInt(6),
                prefix.path("mustAbbreviateTable").asBoolean(true),
                fk.path("enabled").asBoolean(false), pattern, List.copyOf(singular),
                pk.path("enabled").asBoolean(false), pk.path("name").asText("id"),
                Set.copyOf(reserved));
    }

    // ---------------------------------------------------------------- planning

    /**
     * The planned field name of every column of {@code table} that a rule touched (applied or declined), keyed by
     * column name. {@code conceptByTable} gives the concept name of every imported table (foreign-key targets
     * outside it are not references, so they are treated as plain columns).
     */
    static Map<String, Rename> plan(DbIntrospector.Table table, Map<String, String> conceptByTable, Policy policy) {
        Map<String, Rename> out = new LinkedHashMap<>();
        if (policy == null || !policy.anyEnabled()) {
            return out;
        }
        boolean singlePk = table.primaryKey().size() == 1;
        // single-column foreign keys to imported tables: the ones the draft turns into references
        Map<String, String> targetByFkColumn = new HashMap<>();
        Map<String, Integer> fksPerTarget = new HashMap<>();
        for (DbIntrospector.ForeignKey fk : table.foreignKeys()) {
            if (fk.columns().size() == 1 && conceptByTable.containsKey(fk.pkTable())) {
                targetByFkColumn.put(fk.columns().get(0), fk.pkTable());
                fksPerTarget.merge(fk.pkTable(), 1, Integer::sum);
            }
        }
        String prefix = policy.prefixEnabled() ? sharedPrefix(table, targetByFkColumn.keySet(), policy) : null;

        Map<String, String> legacy = new LinkedHashMap<>();
        Map<String, String> proposed = new LinkedHashMap<>();
        Map<String, String> rule = new HashMap<>();
        for (DbIntrospector.Column column : table.columns()) {
            String name = column.name();
            String legacyField = DbIntrospector.NameStyle.camel(name);
            legacy.put(name, legacyField);
            String target = targetByFkColumn.get(name);
            boolean isPk = singlePk && table.primaryKey().get(0).equals(name);
            String candidate = null;
            if (isPk && policy.primaryKeyEnabled()) {
                candidate = policy.primaryKeyName();
                rule.put(name, "primaryKey");
            } else if (!isPk && target != null && policy.foreignKeyEnabled() && fksPerTarget.get(target) == 1
                    && !target.equals(table.name())) {
                candidate = policy.foreignKeyPattern().replace("{target}", lowerFirst(singular(conceptByTable.get(target), policy)));
                rule.put(name, "foreignKey");
            } else if (prefix != null) {
                List<int[]> tokens = tokens(name);
                if (tokens.size() >= 2 && word(name, tokens.get(0)).equalsIgnoreCase(prefix)) {
                    String rest = name.substring(tokens.get(1)[0]);
                    rule.put(name, "prefix");
                    if (Character.isDigit(rest.charAt(0))) {
                        rule.put(name, "digit");
                    } else {
                        candidate = DbIntrospector.NameStyle.camel(rest);
                    }
                }
            }
            if (!rule.containsKey(name)) {
                continue;
            }
            if ("digit".equals(rule.get(name))) {
                proposed.put(name, legacyField);
            } else if (candidate == null || candidate.isEmpty()) {
                rule.put(name, "empty");
                proposed.put(name, legacyField);
            } else if (Character.isDigit(candidate.charAt(0))) {
                rule.put(name, "digit");
                proposed.put(name, legacyField);
            } else if (policy.reservedWords().contains(candidate.toLowerCase(Locale.ROOT))) {
                rule.put(name, "reserved");
                proposed.put(name, legacyField);
            } else {
                proposed.put(name, candidate);
            }
        }
        // Collisions: revert any renamed column whose name another field of the table also has, until stable.
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Integer> counts = new HashMap<>();
            if (!singlePk) {
                counts.put("id", 1); // the draft adds a synthetic id
            }
            for (String column : legacy.keySet()) {
                counts.merge(finalName(column, legacy, proposed).toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            for (Map.Entry<String, String> entry : proposed.entrySet()) {
                String column = entry.getKey();
                if (!entry.getValue().equals(legacy.get(column)) && counts.get(entry.getValue().toLowerCase(Locale.ROOT)) > 1) {
                    entry.setValue(legacy.get(column));
                    rule.put(column, "collision");
                    changed = true;
                }
            }
        }
        for (Map.Entry<String, String> entry : proposed.entrySet()) {
            String column = entry.getKey();
            String legacyField = legacy.get(column);
            if (entry.getValue().equals(legacyField) && isApplyRule(rule.get(column))) {
                continue; // the rule's answer is the legacy name already (an ID column named "ID"): nothing to say
            }
            out.put(column, new Rename(column, legacyField, entry.getValue(), rule.get(column)));
        }
        return out;
    }

    private static boolean isApplyRule(String rule) {
        return "primaryKey".equals(rule) || "foreignKey".equals(rule) || "prefix".equals(rule);
    }

    private static String finalName(String column, Map<String, String> legacy, Map<String, String> proposed) {
        String p = proposed.get(column);
        return p != null ? p : legacy.get(column);
    }

    /** The shared leading word of the non-FK columns, or null when the guards say there is none. */
    static String sharedPrefix(DbIntrospector.Table table, Set<String> fkColumns, Policy policy) {
        int nonFk = 0;
        Map<String, Integer> counts = new TreeMap<>();
        for (DbIntrospector.Column column : table.columns()) {
            if (fkColumns.contains(column.name())) {
                continue;
            }
            nonFk++;
            List<int[]> tokens = tokens(column.name());
            if (tokens.size() >= 2) {
                counts.merge(word(column.name(), tokens.get(0)).toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (best == null || nonFk == 0 || bestCount < policy.minColumns() || (double) bestCount / nonFk < policy.minShare()
                || best.length() < policy.minLength() || best.length() > policy.maxLength()
                || !best.chars().allMatch(Character::isLetter)) {
            return null;
        }
        if (policy.mustAbbreviateTable() && !abbreviates(best, table.name())) {
            return null;
        }
        return best;
    }

    /** {@code arm} abbreviates {@code ARMAZENS}: same first letter, then every letter in order. */
    static boolean abbreviates(String prefix, String tableName) {
        String p = prefix.toLowerCase(Locale.ROOT);
        String t = tableName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (p.isEmpty() || t.isEmpty() || p.charAt(0) != t.charAt(0)) {
            return false;
        }
        int j = 1;
        for (int i = 1; i < p.length(); i++) {
            while (j < t.length() && t.charAt(j) != p.charAt(i)) {
                j++;
            }
            if (j >= t.length()) {
                return false;
            }
            j++;
        }
        return true;
    }

    /**
     * Word boundaries of a legacy name as [start, end) pairs: non-alphanumerics separate, and inside a run a word
     * starts at lower->Upper, letter<->digit, and at the last capital of a capital run followed by a lower-case
     * letter ({@code XMLData -> XML, Data}). {@code CLINOM} is one word.
     */
    static List<int[]> tokens(String name) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                if (start >= 0) {
                    out.add(new int[]{start, i});
                    start = -1;
                }
                continue;
            }
            if (start < 0) {
                start = i;
                continue;
            }
            char prev = name.charAt(i - 1);
            boolean boundary = (Character.isLowerCase(prev) && Character.isUpperCase(c))
                    || (Character.isLetter(prev) && Character.isDigit(c))
                    || (Character.isDigit(prev) && Character.isLetter(c))
                    || (Character.isUpperCase(prev) && Character.isUpperCase(c) && i + 1 < name.length()
                    && Character.isLowerCase(name.charAt(i + 1)));
            if (boundary) {
                out.add(new int[]{start, i});
                start = i;
            }
        }
        if (start >= 0) {
            out.add(new int[]{start, name.length()});
        }
        return out;
    }

    private static String word(String name, int[] token) {
        return name.substring(token[0], token[1]);
    }

    static String singular(String concept, Policy policy) {
        String lower = concept.toLowerCase(Locale.ROOT);
        for (SingularRule rule : policy.singular()) {
            if (!rule.suffix().isEmpty() && lower.endsWith(rule.suffix().toLowerCase(Locale.ROOT))
                    && concept.length() >= rule.minLength()) {
                return concept.substring(0, concept.length() - rule.suffix().length()) + rule.replace();
            }
        }
        return concept;
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
