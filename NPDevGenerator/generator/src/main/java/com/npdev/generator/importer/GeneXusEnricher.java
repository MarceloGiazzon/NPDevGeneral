package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMP-2: GeneXus semantics. Layers what a GeneXus export knows and the database does not onto a draft made
 * by {@link DbIntrospector}: attribute captions become field labels, transactions become concept labels,
 * enumerated domains become {@code enum} fields (the legacy codes stay in the field description and the
 * report), and the transactions' rules become invariant / default CANDIDATES.
 *
 * <p>Nothing here is applied silently: a candidate lives in the report only, with the GeneXus source text
 * and a confidence label, never in the model. Procedures, web panels, SDTs and queries are an inventory of
 * manual-port items whose source text is attached untranslated.
 */
public final class GeneXusEnricher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TYPE_TRANSACTION = "1db606f2-af09-4cf9-a3b5-b481519d28f6";
    private static final String TYPE_DOMAIN = "00972a17-9975-449e-aab1-d26165d51393";
    private static final String TYPE_TABLE = "857ca50e-7905-0000-0007-c5d9ff2975ec";
    private static final String TYPE_PROCEDURE = "84a12160-f59b-4ad7-a683-ea4481ac23e9";
    private static final String TYPE_WEB_PANEL = "c9584656-94b6-4ccd-890f-332d11fc2c25";
    private static final String TYPE_SDT = "447527b5-9210-4523-898b-5dccb17be60a";
    private static final String TYPE_QUERY = "926a06b9-3417-4ab4-9f8c-09c2f626bb1c";
    private static final String TYPE_SUBTYPE_GROUP = "87313f43-5eb2-41d7-9b8c-e8d9f5bf9588";
    private static final String PART_TABLE_KEY = "00000000-0000-0000-0002-000000000004";
    private static final String PART_STRUCTURE = "264be5fb";
    private static final String PART_RULES = "9b0a32a3";
    private static final String PART_EVENTS = "c44bd5ff";
    private static final String PART_PROCEDURE_SOURCE = "528d1c06";

    private GeneXusEnricher() {
    }

    /** The enriched model and report, plus the manual-port source files to write next to them. */
    public record Result(ObjectNode model, ObjectNode report, Map<String, String> files) {
    }

    record EnumItem(String code, String name, String label) {
    }

    record Domain(String name, List<EnumItem> items) {
    }

    record Attr(String name, String caption, String domain) {
    }

    record Level(String name, String description, List<String> keys, List<Level> children) {
    }

    /** Where a GeneXus attribute landed in the draft: the concept, its field node, and whether it became an enum. */
    private record FieldRef(String concept, ObjectNode field, boolean enumerated) {
    }

    public static Result enrich(ObjectNode draftModel, ObjectNode draftReport, Document tablesXml, Document moduleXml) {
        ObjectNode model = draftModel.deepCopy();
        ObjectNode report = draftReport.deepCopy();
        Map<String, String> files = new LinkedHashMap<>();
        ObjectNode gx = report.putObject("genexus");
        gx.put("note", "IMP-2: what the GeneXus export adds to the database draft. Captions, enums and concept labels are "
                + "applied to the model (confidence high: read straight from the export). Rule candidates are NEVER applied; "
                + "they carry the GeneXus source and a confidence label for a person to accept. Procedures, web panels, "
                + "SDTs and queries are an inventory of manual-port items, source attached, not translated.");
        ObjectNode counts = gx.putObject("counts");

        Map<String, Domain> domains = readDomains(tablesXml);
        Map<String, Attr> attrs = readAttributes(tablesXml);
        Map<String, List<String>> tableKeys = readTableKeys(tablesXml);

        // ---- columns -> fields: captions and enums
        Map<String, Map<String, FieldRef>> fieldsByTable = new HashMap<>();
        Map<String, ObjectNode> conceptByName = new HashMap<>();
        for (var concept : model.withArray("concepts")) {
            conceptByName.put(concept.path("name").asText(), (ObjectNode) concept);
        }
        ArrayNode enums = gx.putArray("enums");
        ArrayNode manual = report.withArray("manualItems");
        int columns = 0;
        int matched = 0;
        int captions = 0;
        int enumApplied = 0;
        int enumSkipped = 0;
        int plainDomain = 0;
        for (var tableReport : report.withArray("tables")) {
            String table = tableReport.path("table").asText();
            String conceptName = tableReport.path("concept").asText();
            ObjectNode concept = conceptByName.get(conceptName);
            if (concept == null) {
                continue;
            }
            Map<String, ObjectNode> fieldByName = new HashMap<>();
            for (var f : concept.withArray("fields")) {
                fieldByName.put(f.path("name").asText(), (ObjectNode) f);
            }
            Map<String, FieldRef> byAttribute = fieldsByTable.computeIfAbsent(table.toLowerCase(Locale.ROOT), k -> new HashMap<>());
            var columnMap = tableReport.path("columns");
            for (var it = columnMap.fields(); it.hasNext(); ) {
                var entry = it.next();
                columns++;
                ObjectNode field = fieldByName.get(entry.getValue().asText());
                Attr attr = attrs.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (field == null || attr == null) {
                    continue;
                }
                matched++;
                boolean enumerated = false;
                if (!attr.caption().isBlank()) {
                    ObjectNode ui = field.has("ui") && field.get("ui").isObject() ? (ObjectNode) field.get("ui") : field.putObject("ui");
                    if (!ui.has("label")) {
                        ui.put("label", attr.caption());
                        captions++;
                    }
                }
                Domain domain = attr.domain() == null ? null : domains.get(attr.domain().toLowerCase(Locale.ROOT));
                if (domain != null && domain.items().isEmpty()) {
                    plainDomain++;
                } else if (domain != null) {
                    String why = enumBlocker(field, domain);
                    if (why != null) {
                        enumSkipped++;
                        manual.add(table + "." + entry.getKey() + ": domain " + domain.name() + " is enumerated but " + why
                                + "; left as " + field.path("type").asText() + ".");
                        enums.addObject().put("domain", domain.name()).put("concept", conceptName)
                                .put("field", field.path("name").asText()).put("applied", false).put("reason", why);
                    } else {
                        applyEnum(field, domain);
                        enumerated = true;
                        enumApplied++;
                        ObjectNode e = enums.addObject();
                        e.put("domain", domain.name()).put("concept", conceptName).put("field", field.path("name").asText());
                        e.put("applied", true).put("confidence", "high");
                        e.put("dataNote", "The source column stores the legacy code; the model stores the name. Convert the data "
                                + "(code -> name) when loading.");
                        ObjectNode codes = e.putObject("legacyCodes");
                        domain.items().forEach(item -> codes.put(item.code(), item.name()));
                    }
                }
                byAttribute.put(entry.getKey().toLowerCase(Locale.ROOT), new FieldRef(conceptName, field, enumerated));
            }
        }
        ObjectNode attributeCounts = counts.putObject("attributes");
        attributeCounts.put("columnsInDraft", columns).put("matchedToGeneXusAttribute", matched)
                .put("unmatched", columns - matched).put("captionsApplied", captions);
        counts.putObject("domains").put("inExport", domains.size())
                .put("enumerated", (int) domains.values().stream().filter(d -> !d.items().isEmpty()).count())
                .put("enumAppliedToFields", enumApplied).put("enumSkipped", enumSkipped).put("plainDomainFields", plainDomain);

        if (moduleXml != null) {
            transactions(moduleXml, model, report, gx, counts, conceptByName, fieldsByTable, tableKeys, domains, files);
        }
        report.with("counts").put("manualItems", manual.size());
        return new Result(model, report, files);
    }

    // ---------------------------------------------------------------- enums

    /** Why a domain cannot become an enum on this field, or null when it can. */
    private static String enumBlocker(ObjectNode field, Domain domain) {
        String type = field.path("type").asText();
        if (field.path("id").asBoolean(false)) {
            return "the field is the identity";
        }
        if ("reference".equals(type)) {
            return "the field is a reference";
        }
        if ("enum".equals(type)) {
            return "the field is already an enum";
        }
        if (!List.of("integer", "long", "string", "decimal").contains(type)) {
            return "the column type " + type + " cannot hold an enum";
        }
        Set<String> seen = new HashSet<>();
        for (EnumItem item : domain.items()) {
            if (item.name().isBlank() || !seen.add(item.name().toLowerCase(Locale.ROOT))) {
                return "its value names are blank or repeat";
            }
        }
        return null;
    }

    private static void applyEnum(ObjectNode field, Domain domain) {
        field.put("type", "enum");
        for (String drop : List.of("maxLength", "minLength", "precision", "scale", "min", "max")) {
            field.remove(drop);
        }
        ArrayNode values = MAPPER.createArrayNode();
        StringBuilder codes = new StringBuilder();
        for (EnumItem item : domain.items()) {
            if (item.label().equals(item.name())) {
                values.add(item.name());
            } else {
                values.addObject().put("value", item.name()).put("label", item.label());
            }
            codes.append(codes.length() == 0 ? "" : "; ").append(item.code()).append('=').append(item.name());
        }
        field.set("values", values);
        String old = field.path("description").asText("");
        field.put("description", (old.isEmpty() ? "" : old + ". ") + "Legacy codes (domain " + domain.name() + "): " + codes + ".");
    }

    // ---------------------------------------------------------------- transactions and rules

    private static void transactions(Document moduleXml, ObjectNode model, ObjectNode report, ObjectNode gx, ObjectNode counts,
                                     Map<String, ObjectNode> conceptByName, Map<String, Map<String, FieldRef>> fieldsByTable,
                                     Map<String, List<String>> tableKeys, Map<String, Domain> domains, Map<String, String> files) {
        Map<String, String> conceptByTable = new HashMap<>();
        for (var t : report.withArray("tables")) {
            conceptByTable.put(t.path("table").asText().toLowerCase(Locale.ROOT), t.path("concept").asText());
        }
        Map<String, List<String>> tablesByKeySet = new HashMap<>();
        tableKeys.forEach((table, keys) -> tablesByKeySet.computeIfAbsent(keySet(keys), k -> new ArrayList<>()).add(table));

        ArrayNode txs = gx.putArray("transactions");
        ArrayNode candidates = gx.putArray("ruleCandidates");
        int total = 0;
        int mapped = 0;
        int partial = 0;
        int unmapped = 0;
        int levelsTotal = 0;
        int levelsMapped = 0;
        int statements = 0;
        int plumbing = 0;
        int translated = 0;
        int sourceOnly = 0;
        int defaults = 0;
        int manualRules = 0;
        Set<String> labelled = new HashSet<>();
        for (Element tx : objects(moduleXml, TYPE_TRANSACTION)) {
            total++;
            String name = tx.getAttribute("name");
            Element structure = part(tx, PART_STRUCTURE);
            Level root = structure == null ? null : level(firstChild(structure, "Level"));
            ObjectNode txReport = txs.addObject();
            txReport.put("name", name).put("description", tx.getAttribute("description"));
            ArrayNode levelReports = txReport.putArray("levels");
            List<String> concepts = new ArrayList<>();
            String rootConcept = null;
            if (root != null) {
                List<Level> stack = new ArrayList<>();
                collect(root, stack);
                for (Level lv : stack) {
                    levelsTotal++;
                    String table = tableFor(lv, root, tablesByKeySet, conceptByTable);
                    String concept = table == null ? null : conceptByTable.get(table);
                    ObjectNode lr = levelReports.addObject();
                    lr.put("level", lv.name()).put("description", lv.description());
                    if (concept == null) {
                        lr.put("status", "unmapped").put("reason", table == null
                                ? "no table with this key set (or several; the choice is a human one)"
                                : "the table is not in the draft");
                        continue;
                    }
                    levelsMapped++;
                    lr.put("status", "mapped").put("table", table).put("concept", concept);
                    concepts.add(concept);
                    if (lv == root) {
                        rootConcept = concept;
                    }
                    ObjectNode node = conceptByName.get(concept);
                    if (node != null && !lv.description().isBlank() && labelled.add(concept)) {
                        ObjectNode ui = node.has("ui") && node.get("ui").isObject() ? (ObjectNode) node.get("ui") : node.putObject("ui");
                        if (!ui.has("label")) {
                            ui.put("label", lv.description());
                        }
                    }
                }
            }
            long mappedLevels = levelReports.findValues("status").stream().filter(s -> "mapped".equals(s.asText())).count();
            String status = mappedLevels == 0 ? "unmapped" : mappedLevels == levelReports.size() ? "mapped" : "partial";
            txReport.put("status", status);
            if (rootConcept != null) {
                txReport.put("concept", rootConcept);
            }
            switch (status) {
                case "mapped" -> mapped++;
                case "partial" -> partial++;
                default -> unmapped++;
            }

            Element rulesPart = part(tx, PART_RULES);
            String rulesText = rulesPart == null ? "" : text(firstChild(rulesPart, "Source"));
            for (String statement : statements(rulesText)) {
                statements++;
                RuleKind kind = classify(statement);
                switch (kind) {
                    case PLUMBING -> plumbing++;
                    case ERROR -> {
                        ObjectNode c = errorCandidate(statement, name, concepts, fieldsByTable, conceptByTable, domains, root, tablesByKeySet);
                        candidates.add(c);
                        if ("translated-unverified".equals(c.path("confidence").asText())) {
                            translated++;
                        } else {
                            sourceOnly++;
                        }
                    }
                    case DEFAULT -> {
                        ObjectNode c = defaultCandidate(statement, name, concepts, fieldsByTable, domains);
                        candidates.add(c);
                        if ("manual".equals(c.path("confidence").asText())) {
                            manualRules++;
                        } else {
                            defaults++;
                        }
                    }
                    case ASSIGNMENT -> {
                        ObjectNode c = insertDefault(statement, name, concepts, fieldsByTable, domains);
                        candidates.add(c);
                        if ("manual".equals(c.path("confidence").asText())) {
                            manualRules++;
                        } else {
                            defaults++;
                        }
                    }
                    default -> {
                        manualRules++;
                        candidates.addObject().put("transaction", name).put("kind", kind.label).put("confidence", "manual")
                                .put("sourceText", statement);
                    }
                }
            }
        }
        counts.putObject("transactions").put("total", total).put("mapped", mapped).put("partial", partial)
                .put("unmapped", unmapped).put("levels", levelsTotal).put("levelsMapped", levelsMapped);
        counts.putObject("rules").put("statements", statements)
                .put("notBusinessRule", plumbing).put("invariantTranslated", translated).put("invariantSourceOnly", sourceOnly)
                .put("defaultCandidates", defaults).put("manual", manualRules);
        counts.putObject("note").put("rules", "notBusinessRule = parameter binding and screen behaviour (Parm, NoAccept, NoPrompt, "
                + "control properties), dropped by design. invariantTranslated = an expression is offered, unverified. "
                + "invariantSourceOnly = a business rule whose text needs a person. manual = everything else, text attached.");

        inventory(moduleXml, gx, counts, files);
    }

    private static void collect(Level level, List<Level> out) {
        out.add(level);
        level.children().forEach(c -> collect(c, out));
    }

    /** The table a transaction level persists to: the one whose key set equals the level's keys plus its ancestors'. */
    private static String tableFor(Level level, Level root, Map<String, List<String>> tablesByKeySet, Map<String, String> conceptByTable) {
        List<String> chain = new ArrayList<>();
        if (!pathTo(root, level, chain)) {
            return null;
        }
        List<String> keys = new ArrayList<>(chain);
        List<String> candidates = tablesByKeySet.getOrDefault(keySet(keys), List.of());
        if (candidates.size() > 1) {
            String levelKey = level.name().toLowerCase(Locale.ROOT);
            List<String> byName = candidates.stream().filter(t -> t.equals(levelKey)).toList();
            return byName.size() == 1 ? byName.get(0) : null;
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** Fills {@code keys} with the key attributes along the path root..target; false when target is not under root. */
    private static boolean pathTo(Level from, Level target, List<String> keys) {
        int mark = keys.size();
        keys.addAll(from.keys());
        if (from == target) {
            return true;
        }
        for (Level child : from.children()) {
            if (pathTo(child, target, keys)) {
                return true;
            }
        }
        while (keys.size() > mark) {
            keys.remove(keys.size() - 1);
        }
        return false;
    }

    private static String keySet(List<String> keys) {
        return String.join(",", new java.util.TreeSet<>(keys.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList()));
    }

    private static Level level(Element el) {
        List<String> keys = new ArrayList<>();
        List<Level> children = new ArrayList<>();
        for (Element child : childElements(el)) {
            if ("Attribute".equals(child.getTagName()) && "True".equalsIgnoreCase(child.getAttribute("key"))) {
                keys.add(child.getTextContent().trim());
            } else if ("Level".equals(child.getTagName())) {
                children.add(level(child));
            }
        }
        return new Level(el.getAttribute("Name"), el.getAttribute("Description"), keys, children);
    }

    // ---- rule statements

    private enum RuleKind {
        PLUMBING("plumbing"), ERROR("error"), DEFAULT("default"), MESSAGE("message"), ASSIGNMENT("assignment"),
        CALL("call"), OTHER("other");

        final String label;

        RuleKind(String label) {
            this.label = label;
        }
    }

    private static final Pattern PLUMBING = Pattern.compile(
            "(?is)^(parm\\s*\\(|noaccept\\s*\\(|noprompt\\s*\\(|nocheck\\s*\\(|noclear\\s*\\(|[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+\\s*=).*");
    private static final Pattern PARM_BINDING = Pattern.compile("(?is)^[A-Za-z_]\\w*\\s*=\\s*&\\w+\\s*(if\\b.*)?$");
    private static final Pattern ASSIGN = Pattern.compile("(?is)^([A-Za-z_]\\w*)\\s*=\\s*(.+?)(?:\\s+if\\s+(.+))?$");

    private static RuleKind classify(String statement) {
        String lower = statement.toLowerCase(Locale.ROOT);
        if (PLUMBING.matcher(statement).matches() || PARM_BINDING.matcher(statement).matches()) {
            return RuleKind.PLUMBING;
        }
        if (lower.startsWith("error")) {
            return RuleKind.ERROR;
        }
        if (lower.startsWith("default")) {
            return RuleKind.DEFAULT;
        }
        if (lower.startsWith("msg")) {
            return RuleKind.MESSAGE;
        }
        if (lower.contains(".call(") || lower.startsWith("call(")) {
            return RuleKind.CALL;
        }
        if (ASSIGN.matcher(statement).matches()) {
            return RuleKind.ASSIGNMENT;
        }
        return RuleKind.OTHER;
    }

    /** Rule text without comments, split into statements at ';' outside quotes; GeneXus event tags are dropped. */
    static List<String> statements(String source) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char ch = source.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                cur.append('\n');
            } else if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                cur.append(' ');
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                cur.append(ch);
            } else if (ch == ';') {
                flush(cur, out);
            } else {
                cur.append(ch);
            }
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String s = cur.toString().replaceAll("(?i)\\[(web|win|ios|android|mobile)\\]", " ").replaceAll("[{}]", " ")
                .replaceAll("\\s+", " ").trim();
        cur.setLength(0);
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    private static ObjectNode errorCandidate(String statement, String transaction, List<String> concepts,
                                             Map<String, Map<String, FieldRef>> fieldsByTable, Map<String, String> conceptByTable,
                                             Map<String, Domain> domains, Level root, Map<String, List<String>> tablesByKeySet) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("transaction", transaction).put("kind", "invariant").put("sourceText", statement);
        Matcher m = Pattern.compile("(?is)^error\\s*\\(\\s*(?:'((?:[^']|'')*)'|\"([^\"]*)\")\\s*\\)\\s*(?:if\\s+(.+))?$").matcher(statement);
        if (!m.matches()) {
            return c.put("confidence", "manual").put("why", "the message is not a plain string");
        }
        c.put("message", m.group(1) != null ? m.group(1).replace("''", "'") : m.group(2));
        if (m.group(3) == null) {
            return c.put("confidence", "manual").put("why", "an unconditional Error");
        }
        c.put("gxCondition", m.group(3));
        String expr = translate(m.group(3), concepts, fieldsByTable, domains, c);
        if (expr == null) {
            return c.put("confidence", "manual");
        }
        // Error fires when the condition holds; the invariant is the negation.
        c.put("expression", "!(" + expr + ")");
        return c.put("confidence", "translated-unverified");
    }

    private static ObjectNode defaultCandidate(String statement, String transaction, List<String> concepts,
                                               Map<String, Map<String, FieldRef>> fieldsByTable, Map<String, Domain> domains) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("transaction", transaction).put("kind", "default").put("sourceText", statement);
        Matcher m = Pattern.compile("(?is)^default\\s*\\(\\s*([A-Za-z_]\\w*)\\s*,\\s*(.+)\\)$").matcher(statement);
        if (!m.matches()) {
            return c.put("confidence", "manual").put("why", "not the Default(attribute, value) form");
        }
        String field = resolve(m.group(1), concepts, fieldsByTable);
        String value = literal(m.group(2).trim(), domains);
        if (field == null || value == null) {
            return c.put("confidence", "manual").put("why", field == null ? "the attribute is not in the draft" : "the value is not a literal");
        }
        c.put("attribute", m.group(1)).put("field", field).put("value", value);
        return c.put("confidence", "translated-unverified");
    }

    /** {@code attr = literal if &Mode = TrnMode.Insert} is a default for new records; any other assignment stays manual. */
    private static ObjectNode insertDefault(String statement, String transaction, List<String> concepts,
                                            Map<String, Map<String, FieldRef>> fieldsByTable, Map<String, Domain> domains) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("transaction", transaction).put("kind", "assignment").put("sourceText", statement);
        Matcher m = ASSIGN.matcher(statement);
        if (!m.matches() || m.group(3) == null || !m.group(3).replaceAll("\s+", "").equalsIgnoreCase("&Mode=TrnMode.Insert")) {
            return c.put("confidence", "manual").put("why", "an assignment that is not conditioned on Insert alone");
        }
        String field = resolve(m.group(1), concepts, fieldsByTable);
        String value = literal(m.group(2).trim(), domains);
        if (field == null || value == null) {
            return c.put("confidence", "manual").put("why", field == null ? "the attribute is not in the draft" : "the value is not a literal");
        }
        c.put("kind", "default").put("when", "insert").put("attribute", m.group(1)).put("field", field).put("value", value);
        return c.put("confidence", "translated-unverified");
    }

    /** The field name an attribute became, searching the transaction's mapped concepts; null if absent or ambiguous. */
    private static String resolve(String attribute, List<String> concepts, Map<String, Map<String, FieldRef>> fieldsByTable) {
        FieldRef ref = ref(attribute, concepts, fieldsByTable);
        return ref == null ? null : ref.field().path("name").asText();
    }

    private static FieldRef ref(String attribute, List<String> concepts, Map<String, Map<String, FieldRef>> fieldsByTable) {
        // The transaction's concepts are listed root first; GeneXus resolves an attribute to the nearest level that has it.
        for (String concept : concepts) {
            for (Map<String, FieldRef> byAttribute : fieldsByTable.values()) {
                FieldRef r = byAttribute.get(attribute.toLowerCase(Locale.ROOT));
                if (r != null && r.concept().equals(concept)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** A quoted string, a number or a Domain.Value of an enumerated domain as a model literal; null otherwise. */
    private static String literal(String text, Map<String, Domain> domains) {
        if (text.matches("-?\\d+(\\.\\d+)?")) {
            return text;
        }
        if (text.matches("'[^']*'")) {
            return text;
        }
        Matcher m = Pattern.compile("([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)").matcher(text);
        if (m.matches()) {
            Domain d = domains.get(m.group(1).toLowerCase(Locale.ROOT));
            if (d != null) {
                for (EnumItem item : d.items()) {
                    if (item.name().equalsIgnoreCase(m.group(2))) {
                        return "'" + item.name().replace("'", "\\'") + "'";
                    }
                }
            }
        }
        return null;
    }

    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(<>|>=|<=|[=<>()]|'[^']*'|\\d+(?:\\.\\d+)?|&\\w+|[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*(?:\\s*\\()?)");

    /** A GeneXus condition as a model expression, or null (with the reason in {@code why}). Conservative on purpose. */
    private static String translate(String condition, List<String> concepts, Map<String, Map<String, FieldRef>> fieldsByTable,
                                    Map<String, Domain> domains, ObjectNode out) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(condition);
        int at = 0;
        while (at < condition.length() && m.find(at) && m.start() == at) {
            tokens.add(m.group(1));
            at = m.end();
        }
        if (condition.substring(at).trim().length() > 0) {
            out.put("why", "uses syntax the translator does not read: " + condition.substring(at).trim());
            return null;
        }
        tokens = groupNot(tokens);
        if (tokens == null) {
            out.put("why", "a 'not' that is not followed by a parenthesis or a single comparison");
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String concept = null;
        FieldRef lastEnumField = null;
        boolean hasEnum = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String lower = t.toLowerCase(Locale.ROOT);
            if (t.startsWith("&") || t.endsWith("(") && !t.equals("(")) {
                out.put("why", "uses a variable or a function: " + t);
                return null;
            }
            switch (lower) {
                case "and" -> sb.append(" && ");
                case "or" -> sb.append(" || ");
                case "not" -> sb.append("!");
                case "=" -> sb.append(" == ");
                case "<>" -> sb.append(" != ");
                case "(", ")", ">", "<", ">=", "<=" -> sb.append(t.length() > 1 || t.equals(">") || t.equals("<") ? " " + t + " " : t);
                default -> {
                    if (t.startsWith("'")) {
                        sb.append(t);
                    } else if (t.matches("\\d+(\\.\\d+)?")) {
                        // a number compared with an enumerated field is a legacy code; the model holds the name
                        if (lastEnumField != null) {
                            String name = legacyName(lastEnumField, t);
                            if (name == null) {
                                out.put("why", "code " + t + " is not a value of the enumerated field");
                                return null;
                            }
                            sb.append("'").append(name).append("'");
                        } else if (hasEnum && !(i > 0 && tokens.get(i - 1).matches("<>|>=|<=|[=<>]"))) {
                            out.put("why", "a number that is not the right-hand side of a comparison cannot be read as a legacy code");
                            return null;
                        } else {
                            sb.append(t);
                        }
                    } else if (t.contains(".")) {
                        String lit = literal(t, domains);
                        if (lit == null) {
                            out.put("why", "uses a member access: " + t);
                            return null;
                        }
                        sb.append(lit);
                    } else {
                        FieldRef ref = ref(t, concepts, fieldsByTable);
                        if (ref == null) {
                            out.put("why", "attribute " + t + " is not a column of the draft");
                            return null;
                        }
                        if (concept != null && !concept.equals(ref.concept())) {
                            out.put("why", "the condition spans several concepts (" + concept + ", " + ref.concept() + ")");
                            return null;
                        }
                        concept = ref.concept();
                        lastEnumField = ref.enumerated() ? ref : null;
                        hasEnum |= ref.enumerated();
                        sb.append(ref.field().path("name").asText());
                    }
                }
            }
            if (lower.equals("and") || lower.equals("or")) {
                lastEnumField = null;
            }
        }
        if (concept == null) {
            out.put("why", "the condition mentions no field of the draft");
            return null;
        }
        out.put("concept", concept);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * GeneXus {@code not a = b} negates the whole comparison; the model's {@code !} binds tighter. Wrap the comparison
     * in parentheses, or give up (null) when the shape after {@code not} is anything but a parenthesis or one comparison.
     */
    private static List<String> groupNot(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            out.add(t);
            if (!t.equalsIgnoreCase("not") || i + 1 >= tokens.size() || tokens.get(i + 1).equals("(")) {
                continue;
            }
            if (i + 3 >= tokens.size() || !tokens.get(i + 2).matches("<>|>=|<=|[=<>]")) {
                return null;
            }
            out.add("(");
            out.add(tokens.get(i + 1));
            out.add(tokens.get(i + 2));
            out.add(tokens.get(i + 3));
            out.add(")");
            i += 3;
        }
        return out;
    }

    /** The enum value name for a legacy code on an enum-converted field, using the code list kept in its description. */
    private static String legacyName(FieldRef ref, String code) {
        Matcher m = Pattern.compile("(?:^|[:;]\\s*)" + Pattern.quote(code) + "=([^;.]+)").matcher(ref.field().path("description").asText());
        return m.find() ? m.group(1).trim() : null;
    }

    // ---------------------------------------------------------------- inventory of manual-port items

    private static void inventory(Document moduleXml, ObjectNode gx, ObjectNode counts, Map<String, String> files) {
        ObjectNode port = gx.putObject("manualPort");
        port.put("note", "Not translated. Each item's GeneXus source is written next to the report under manual-port/.");
        ObjectNode portCounts = counts.putObject("manualPort");
        portCounts.put("procedures", portKind(moduleXml, TYPE_PROCEDURE, "procedures", PART_PROCEDURE_SOURCE, port, files));
        portCounts.put("webPanels", portKind(moduleXml, TYPE_WEB_PANEL, "webpanels", PART_EVENTS, port, files));
        portCounts.put("sdts", portKind(moduleXml, TYPE_SDT, "sdts", null, port, files));
        portCounts.put("queries", portKind(moduleXml, TYPE_QUERY, "queries", null, port, files));
        portCounts.put("subtypeGroups", objects(moduleXml, TYPE_SUBTYPE_GROUP).size());
    }

    private static int portKind(Document doc, String type, String folder, String sourcePart, ObjectNode port, Map<String, String> files) {
        ArrayNode list = port.putArray(folder);
        int n = 0;
        for (Element o : objects(doc, type)) {
            n++;
            ObjectNode item = list.addObject();
            item.put("name", o.getAttribute("name")).put("description", o.getAttribute("description"));
            StringBuilder source = new StringBuilder();
            for (String partType : new String[] {sourcePart, PART_RULES}) {
                Element p = partType == null ? null : part(o, partType);
                String text = p == null ? "" : text(firstChild(p, "Source"));
                if (!text.isBlank()) {
                    source.append("// ---- part ").append(partType).append('\n').append(text.strip()).append('\n');
                }
            }
            item.put("sourceChars", source.length());
            if (source.length() > 0) {
                String path = "manual-port/" + folder + "/" + o.getAttribute("name").replaceAll("[^A-Za-z0-9_.-]", "_") + ".gx.txt";
                files.put(path, source.toString());
                item.put("sourceFile", path);
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- reading the export

    private static Map<String, Domain> readDomains(Document doc) {
        Map<String, Domain> out = new LinkedHashMap<>();
        for (Element o : objects(doc, TYPE_DOMAIN)) {
            String values = property(o, "IDEnumDefinedValues");
            List<EnumItem> items = new ArrayList<>();
            if (values != null) {
                for (String part : values.split(";")) {
                    String[] f = part.trim().split(",", 3);
                    if (f.length >= 2 && !f[0].isBlank()) {
                        items.add(new EnumItem(f[0].trim(), f[1].trim(), f.length > 2 && !f[2].isBlank() ? f[2].trim() : f[1].trim()));
                    }
                }
            }
            out.put(o.getAttribute("name").toLowerCase(Locale.ROOT), new Domain(o.getAttribute("name"), items));
        }
        return out;
    }

    private static Map<String, Attr> readAttributes(Document doc) {
        Map<String, Attr> out = new HashMap<>();
        Element root = doc.getDocumentElement();
        for (Element section : childElements(root)) {
            if (!"Attributes".equals(section.getTagName())) {
                continue;
            }
            for (Element a : childElements(section)) {
                String title = property(a, "ContextualTitle");
                String caption = title != null && !title.isBlank() ? title : a.getAttribute("description");
                String based = property(a, "idBasedOn");
                String domain = based != null && based.startsWith("Domain:") ? based.substring("Domain:".length()) : null;
                out.put(a.getAttribute("name").toLowerCase(Locale.ROOT), new Attr(a.getAttribute("name"), caption, domain));
            }
        }
        return out;
    }

    private static Map<String, List<String>> readTableKeys(Document doc) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Element o : objects(doc, TYPE_TABLE)) {
            Element key = part(o, PART_TABLE_KEY);
            List<String> keys = new ArrayList<>();
            if (key != null) {
                Element k = firstChild(key, "Key");
                if (k != null) {
                    for (Element item : childElements(k)) {
                        keys.add(item.getTextContent().trim());
                    }
                }
            }
            out.put(o.getAttribute("name").toLowerCase(Locale.ROOT), keys);
        }
        return out;
    }

    /** Top-level objects of a type (the nested GeneXus library modules are deliberately not entered). */
    private static List<Element> objects(Document doc, String type) {
        List<Element> out = new ArrayList<>();
        for (Element section : childElements(doc.getDocumentElement())) {
            if ("Objects".equals(section.getTagName())) {
                for (Element o : childElements(section)) {
                    if ("Object".equals(o.getTagName()) && o.getAttribute("type").equals(type)) {
                        out.add(o);
                    }
                }
            }
        }
        return out;
    }

    private static Element part(Element object, String typePrefix) {
        for (Element p : childElements(object)) {
            if ("Part".equals(p.getTagName()) && p.getAttribute("type").startsWith(typePrefix)) {
                return p;
            }
        }
        return null;
    }

    /** A property from the object's OWN Properties block (not a part's). */
    private static String property(Element object, String name) {
        Element props = firstChild(object, "Properties");
        if (props == null) {
            return null;
        }
        for (Element p : childElements(props)) {
            Element n = firstChild(p, "Name");
            if (n != null && name.equals(n.getTextContent().trim())) {
                Element v = firstChild(p, "Value");
                return v == null ? null : v.getTextContent();
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Element c : childElements(parent)) {
            if (tag.equals(c.getTagName())) {
                return c;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private static String text(Element e) {
        return e == null ? "" : e.getTextContent();
    }
}
