package com.finalexec.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@code definition/seeds/*.json} (copied at build time by Build-NpdevApp.ps1 into the
 * {@code npdev-seed/data-seeds/} classpath folder, mirroring the WorkspaceMenuSeeder convention
 * -- see {@link com.finalexec.workspace.WorkspaceMenuSeeder}) and creates the declared records
 * through {@link ConceptGateway#save}, the same governed write path
 * {@code DefaultProcedureExecutor.saveConcept()} already uses, proven across every WmsOffice
 * Procedure. Deliberately on-demand (triggered by {@link DataSeedAdminController}), not a
 * boot-time {@code ApplicationRunner} like WorkspaceMenuSeeder -- seeds are meant to be run
 * repeatedly / selectively by an operator, not auto-applied once.
 *
 * <p>Two seed kinds share one file shape ({@code records: [{alias?, concept, id?, data} |
 * {concept, repeatOver, data} | {concept, count, data}]}): {@code smart} expands
 * {@code repeatOver}/{@code count} blocks and resolves {@code $ref:alias} placeholders against
 * aliases declared earlier in the same file; {@code raw} saves every record exactly as written and
 * rejects (fail-fast, before any record is saved) any record that uses
 * {@code alias}/{@code repeatOver}/{@code count}/a {@code $ref:}/{@code $gen:} value, since raw
 * seeds don't support templating.</p>
 *
 * <p><b>R3.2 -- generative seeds.</b> {@code count} is a shorthand for {@code repeatOver} with no
 * index variables: it bulk-generates N copies of {@code data}, meant to pair with {@code $gen:}
 * tokens rather than {@code $var} substitution. A {@code smart}-kind {@code data} field whose value
 * is exactly {@code "$gen:<generator>[:<args>]"} is replaced with a generated value: {@code name},
 * {@code words[:count]}, {@code date-range:<minIso>:<maxIso>}, {@code decimal-range:<min>:<max>},
 * {@code enum-pick:<v1>,<v2>,...}, and {@code ref-pick-random:<concept>} (a random id among that
 * concept's records already saved earlier in this same run -- so, like {@code $ref:alias}, the
 * referent's seed records must be declared EARLIER in the file's {@code records} array; this is
 * the existing sequential save order every seed already had, not a new guarantee). Resolution
 * happens against a single {@link Random} seeded from {@code seedId.hashCode()} at the start of
 * {@link #run}, so two runs of the same seed file draw the identical generator sequence and
 * produce identical generated values -- record ids themselves stay {@link UUID#randomUUID()} (see
 * that call site for why: making ids deterministic too would make re-running the same seed against
 * a non-empty database collide on primary key, which today's random ids never do).</p>
 */
@Service
public class SeedDataService {

    private static final String MANIFEST_PATH = "classpath:npdev-seed/data-seeds/index.json";
    private static final String SEED_PATH_PREFIX = "classpath:npdev-seed/data-seeds/";
    private static final String KIND_SMART = "smart";
    private static final String KIND_RAW = "raw";
    private static final String REF_PREFIX = "$ref:";
    private static final String GEN_PREFIX = "$gen:";
    private static final Pattern GEN_TOKEN = Pattern.compile("^\\$gen:([a-zA-Z0-9_-]+)(?::(.*))?$", Pattern.DOTALL);

    // Small, fixed built-in corpora -- deterministic given the seeded Random's draw index, not
    // meant to be exhaustive or configurable. A model that needs domain-specific generated text
    // should declare literal values or repeatOver instead; these exist for demo-ready volume, not
    // realism.
    private static final String[] GEN_FIRST_NAMES = {
            "Ana", "Bruno", "Carla", "Diego", "Elena", "Felipe", "Gabriela", "Hugo", "Ines", "Joao",
            "Karen", "Lucas", "Maria", "Nicolas", "Olivia", "Pedro", "Quintino", "Rita", "Sofia", "Tiago"
    };
    private static final String[] GEN_LAST_NAMES = {
            "Alves", "Barros", "Costa", "Duarte", "Esteves", "Ferreira", "Gomes", "Henriques", "Iglesias", "Jardim",
            "Klein", "Lopes", "Martins", "Nunes", "Oliveira", "Pereira", "Queiroz", "Ramos", "Silva", "Teixeira"
    };
    private static final String[] GEN_WORDS = {
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do",
            "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "enim"
    };

    private final ResourceLoader resourceLoader;
    private final ConceptGateway conceptGateway;
    private final ObjectMapper objectMapper;

    public SeedDataService(ResourceLoader resourceLoader, ConceptGateway conceptGateway, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.conceptGateway = conceptGateway;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listAvailable() {
        Resource resource = resourceLoader.getResource(MANIFEST_PATH);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode manifest = objectMapper.readTree(in);
            List<Map<String, Object>> entries = new ArrayList<>();
            if (manifest.isArray()) {
                for (JsonNode entry : manifest) {
                    entries.add(manifestRow(entry));
                }
            } else if (manifest.isObject()) {
                // REG-189: PowerShell's `ConvertTo-Json` unrolls a single-element array through
                // the pipeline, so a manifest built from exactly one definition/seeds/*.json file
                // is written as a bare object rather than a one-element array. Treat it as the
                // one-element manifest it represents rather than silently returning List.of() --
                // an empty list here is indistinguishable from "this app declares no seeds", which
                // is exactly the wrong-answer shape this codebase rejects (see DataSeedAdminController
                // callers such as R7.2's "Load sample data" UI action). The writer is fixed
                // (Build-NpdevApp.ps1 now forces array serialization at this call site), so this
                // branch exists to recover already-built apps without regeneration.
                entries.add(manifestRow(manifest));
            } else if (!manifest.isNull() && !manifest.isMissingNode()) {
                throw new SeedLoadException(
                        "Seed manifest at " + MANIFEST_PATH + " is neither a JSON array nor an object (found "
                                + manifest.getNodeType() + ") -- cannot list available seeds", null);
            }
            return entries;
        } catch (IOException exception) {
            throw new SeedLoadException("Failed to read seed manifest: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> manifestRow(JsonNode entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", textOrNull(entry, "id"));
        row.put("label", textOrNull(entry, "label"));
        row.put("description", textOrNull(entry, "description"));
        row.put("kind", textOrDefault(entry, "kind", KIND_SMART));
        return row;
    }

    public SeedRunResult run(String seedId, ExecutionContext context) {
        if (seedId == null || seedId.isBlank()) {
            throw new IllegalArgumentException("seedId is required");
        }
        JsonNode seedFile = loadSeedFile(seedId);
        String kind = textOrDefault(seedFile, "kind", KIND_SMART);
        List<JsonNode> declaredRecords = new ArrayList<>();
        JsonNode recordsNode = seedFile.get("records");
        if (recordsNode != null && recordsNode.isArray()) {
            recordsNode.forEach(declaredRecords::add);
        }
        return runRecords(seedId, kind, declaredRecords, context);
    }

    /**
     * R8.8: the shared expand+resolve+save core {@link #run(String, ExecutionContext)} already had
     * -- extracted, unchanged, so {@code com.finalexec.seed.ModelSeedRunner} (the boot-time,
     * idempotent executor for model/pack-declared {@code seeds[]}) can drive it directly against
     * records built from the compiled model, WITHOUT going through the {@code definition/seeds/
     * *.json} classpath-file convention {@link #run} reads from. Every record is still saved
     * through {@link ConceptGateway#save}, the same governed write path -- this method changes
     * nothing about HOW a record is expanded or saved, only WHERE the declared records come from.
     *
     * @param runId a stable id for this run, used only to seed {@code $gen}'s {@link Random} (so
     *              two runs sharing the same id draw the identical generator sequence) and to label
     *              the returned {@link SeedRunResult} -- {@link #run} always passes its own {@code
     *              seedId} here, preserving that method's existing behavior exactly.
     */
    public SeedRunResult runRecords(String runId, String kind, List<JsonNode> declaredRecords, ExecutionContext context) {
        if (!KIND_SMART.equals(kind) && !KIND_RAW.equals(kind)) {
            throw new IllegalArgumentException(
                    "Unsupported seed kind '" + kind + "' for seed " + runId + " (expected 'smart' or 'raw')");
        }
        if (KIND_RAW.equals(kind)) {
            validateRawRecords(runId, declaredRecords);
        }

        Map<String, String> aliasToId = new LinkedHashMap<>();
        Map<String, Integer> createdCounts = new LinkedHashMap<>();
        // R3.2: one RNG per run, seeded by the run's own id (String.hashCode() is JLS-specified
        // and stable across JVMs/runs) so two runs of the same seed file draw the identical
        // sequence of $gen values -- the reproducibility the roadmap item exists for. idsByConcept
        // is the ref-pick-random pool: every id actually saved this run, grouped by concept, in
        // save order -- built up as we go, so a $gen:ref-pick-random:<concept> can only ever see
        // ids from concepts whose seed records were declared (and therefore already fully saved)
        // earlier in this same file.
        Random random = new Random(runId.hashCode());
        Map<String, List<String>> idsByConcept = new LinkedHashMap<>();
        long startedAt = System.currentTimeMillis();

        for (int recordIndex = 0; recordIndex < declaredRecords.size(); recordIndex++) {
            JsonNode declared = declaredRecords.get(recordIndex);
            List<ExpandedRecord> expanded = KIND_SMART.equals(kind)
                    ? expandSmartRecord(declared)
                    : expandRawRecord(declared);

            for (ExpandedRecord record : expanded) {
                try {
                    Map<String, Object> data = KIND_SMART.equals(kind)
                            ? resolveReferences(resolveGenerators(record.data(), random, idsByConcept), aliasToId)
                            : record.data();
                    String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
                    // The concept schema declares "id" as a required field, and
                    // DefaultConceptGateway's semantic policy validates required-field presence
                    // against the data map itself (not the separate ConceptWriteRequest.id param)
                    // -- confirmed live: omitting this produced "Required concept field is
                    // missing: User.id" even though id was already passed to ConceptWriteRequest.
                    Map<String, Object> dataWithId = new LinkedHashMap<>(data);
                    dataWithId.put("id", id);
                    conceptGateway.save(new ConceptWriteRequest(record.concept(), id, null, dataWithId), context);
                    createdCounts.merge(record.concept(), 1, Integer::sum);
                    idsByConcept.computeIfAbsent(record.concept(), key -> new ArrayList<>()).add(id);
                    if (record.alias() != null) {
                        aliasToId.put(record.alias(), id);
                    }
                } catch (RuntimeException exception) {
                    return SeedRunResult.failure(
                            runId, kind, createdCounts,
                            recordIndex, record.alias(), record.concept(),
                            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                            System.currentTimeMillis() - startedAt
                    );
                }
            }
        }
        return SeedRunResult.success(runId, kind, createdCounts, System.currentTimeMillis() - startedAt);
    }

    private JsonNode loadSeedFile(String seedId) {
        String safeId = seedId.replaceAll("[^a-zA-Z0-9_-]", "");
        if (!safeId.equals(seedId)) {
            throw new IllegalArgumentException("Invalid seedId: " + seedId);
        }
        Resource resource = resourceLoader.getResource(SEED_PATH_PREFIX + safeId + ".json");
        if (!resource.exists()) {
            throw new SeedNotFoundException("Seed not found: " + seedId);
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException exception) {
            throw new SeedLoadException("Failed to read seed " + seedId + ": " + exception.getMessage(), exception);
        }
    }

    private void validateRawRecords(String seedId, List<JsonNode> declaredRecords) {
        for (int i = 0; i < declaredRecords.size(); i++) {
            JsonNode record = declaredRecords.get(i);
            if (record.has("alias")) {
                throw new IllegalArgumentException(
                        "Raw seed '" + seedId + "' record[" + i + "] declares 'alias' -- raw seeds don't support templating");
            }
            if (record.has("repeatOver")) {
                throw new IllegalArgumentException(
                        "Raw seed '" + seedId + "' record[" + i + "] declares 'repeatOver' -- raw seeds don't support templating");
            }
            if (record.has("count")) {
                throw new IllegalArgumentException(
                        "Raw seed '" + seedId + "' record[" + i + "] declares 'count' -- raw seeds don't support templating");
            }
            JsonNode data = record.get("data");
            if (data != null && data.isObject()) {
                for (var it = data.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode value = entry.getValue();
                    if (value.isTextual() && value.asText().startsWith(REF_PREFIX)) {
                        throw new IllegalArgumentException(
                                "Raw seed '" + seedId + "' record[" + i + "] field '" + entry.getKey()
                                        + "' looks like a $ref placeholder ('" + value.asText()
                                        + "') -- raw seeds don't resolve $ref, only 'smart' seeds do");
                    }
                    if (value.isTextual() && value.asText().startsWith(GEN_PREFIX)) {
                        throw new IllegalArgumentException(
                                "Raw seed '" + seedId + "' record[" + i + "] field '" + entry.getKey()
                                        + "' looks like a $gen generator token ('" + value.asText()
                                        + "') -- raw seeds don't resolve $gen, only 'smart' seeds do");
                    }
                }
            }
        }
    }

    private List<ExpandedRecord> expandRawRecord(JsonNode declared) {
        String concept = requireConcept(declared);
        String id = textOrNull(declared, "id");
        Map<String, Object> data = toMap(declared.get("data"));
        return List.of(new ExpandedRecord(null, concept, id, data));
    }

    private List<ExpandedRecord> expandSmartRecord(JsonNode declared) {
        String concept = requireConcept(declared);
        JsonNode repeatOver = declared.get("repeatOver");
        JsonNode countNode = declared.get("count");
        boolean hasRepeatOver = repeatOver != null && !repeatOver.isNull();
        boolean hasCount = countNode != null && !countNode.isNull();
        if (hasRepeatOver && hasCount) {
            throw new IllegalArgumentException(
                    "Seed record for concept '" + concept + "' declares both 'repeatOver' and 'count' -- use only one bulk-generation mechanism");
        }
        // R3.2: 'count' is the shorthand -- N copies of 'data' with no index variables, meant to
        // pair with $gen:* tokens (resolved later, at save time, in run()) rather than $var
        // substitution. Expansion here only replicates the template; it deliberately does NOT
        // resolve $gen tokens, so every copy shares the same unresolved template object -- safe
        // because resolveGenerators()/resolveReferences() always build a NEW map and never mutate
        // their input.
        if (hasCount) {
            if (declared.has("alias")) {
                throw new IllegalArgumentException(
                        "Seed record for concept '" + concept + "' declares both 'alias' and 'count' -- bulk-generated rows can't be aliased");
            }
            if (!countNode.isIntegralNumber() || countNode.asInt() < 1) {
                throw new IllegalArgumentException(
                        "Seed record for concept '" + concept + "' has 'count' that is not a positive integer");
            }
            int count = countNode.asInt();
            JsonNode template = declared.get("data");
            if (template == null) {
                throw new IllegalArgumentException("Seed record with 'count' is missing 'data' template");
            }
            Map<String, Object> data = toMap(template);
            List<ExpandedRecord> results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                results.add(new ExpandedRecord(null, concept, null, data));
            }
            return results;
        }
        if (!hasRepeatOver) {
            String alias = textOrNull(declared, "alias");
            Map<String, Object> data = toMap(declared.get("data"));
            return List.of(new ExpandedRecord(alias, concept, null, data));
        }
        if (declared.has("alias")) {
            throw new IllegalArgumentException(
                    "Seed record for concept '" + concept + "' declares both 'alias' and 'repeatOver' -- bulk-generated rows can't be aliased");
        }

        JsonNode varsNode = repeatOver.get("vars");
        if (varsNode == null || !varsNode.isObject() || varsNode.isEmpty()) {
            throw new IllegalArgumentException("Seed record for concept '" + concept + "' has 'repeatOver' with no 'vars'");
        }
        List<String> varNames = new ArrayList<>();
        List<int[]> varRanges = new ArrayList<>();
        for (var it = varsNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode range = entry.getValue();
            if (!range.isArray() || range.size() != 2) {
                throw new IllegalArgumentException("repeatOver.vars." + entry.getKey() + " must be a [min,max] pair");
            }
            varNames.add(entry.getKey());
            varRanges.add(new int[]{range.get(0).asInt(), range.get(1).asInt()});
        }

        JsonNode template = declared.get("data");
        if (template == null) {
            throw new IllegalArgumentException("Seed record with 'repeatOver' is missing 'data' template");
        }
        List<ExpandedRecord> results = new ArrayList<>();
        Consumer<Map<String, Integer>> onComplete = assignment ->
                results.add(new ExpandedRecord(null, concept, null, substituteVars(template, assignment)));
        expandCartesian(varNames, varRanges, 0, new LinkedHashMap<>(), onComplete);
        return results;
    }

    private void expandCartesian(
            List<String> varNames, List<int[]> varRanges, int depth,
            Map<String, Integer> assignment, Consumer<Map<String, Integer>> onComplete
    ) {
        if (depth == varNames.size()) {
            onComplete.accept(new LinkedHashMap<>(assignment));
            return;
        }
        String name = varNames.get(depth);
        int[] range = varRanges.get(depth);
        for (int value = range[0]; value <= range[1]; value++) {
            assignment.put(name, value);
            expandCartesian(varNames, varRanges, depth + 1, assignment, onComplete);
        }
        assignment.remove(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> substituteVars(JsonNode template, Map<String, Integer> assignment) {
        Object raw = objectMapper.convertValue(template, Object.class);
        return (Map<String, Object>) substituteValue(raw, assignment);
    }

    private Object substituteValue(Object value, Map<String, Integer> assignment) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("$") && assignment.containsKey(trimmed.substring(1))) {
                return assignment.get(trimmed.substring(1));
            }
            String replaced = text;
            for (Map.Entry<String, Integer> entry : assignment.entrySet()) {
                replaced = replaced.replace("$" + entry.getKey(), String.valueOf(entry.getValue()));
            }
            return replaced;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), substituteValue(v, assignment)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(substituteValue(item, assignment));
            }
            return result;
        }
        return value;
    }

    private Map<String, Object> resolveReferences(Map<String, Object> data, Map<String, String> aliasToId) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        data.forEach((key, value) -> resolved.put(key, resolveValue(value, aliasToId)));
        return resolved;
    }

    private Object resolveValue(Object value, Map<String, String> aliasToId) {
        if (value instanceof String text && text.startsWith(REF_PREFIX)) {
            String alias = text.substring(REF_PREFIX.length());
            String resolvedId = aliasToId.get(alias);
            if (resolvedId == null) {
                throw new IllegalArgumentException(
                        "Unresolved $ref:" + alias + " -- alias not declared before this point in the seed file");
            }
            return resolvedId;
        }
        return value;
    }

    /**
     * R3.2: resolves top-level {@code $gen:} tokens in a record's data, the same shallow
     * (top-level-fields-only) shape {@link #resolveReferences} already uses for {@code $ref:} --
     * concept field values are flat by convention (schema: "same shape as a POST /api/&lt;concept&gt;
     * body"), so a deeper walk was not needed for {@code $ref:} and is not needed here either.
     */
    private Map<String, Object> resolveGenerators(Map<String, Object> data, Random random, Map<String, List<String>> idsByConcept) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        data.forEach((key, value) -> resolved.put(key, resolveGeneratorValue(value, random, idsByConcept)));
        return resolved;
    }

    private Object resolveGeneratorValue(Object value, Random random, Map<String, List<String>> idsByConcept) {
        if (!(value instanceof String text) || !text.startsWith(GEN_PREFIX)) {
            return value;
        }
        Matcher matcher = GEN_TOKEN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Malformed $gen token \"" + text + "\" -- expected \"$gen:<generator>[:<args>]\"");
        }
        String generator = matcher.group(1);
        String args = matcher.group(2);
        return switch (generator) {
            case "name" -> genName(random);
            case "words" -> genWords(args, random);
            case "date-range" -> genDateRange(args, random);
            case "decimal-range" -> genDecimalRange(args, random);
            case "enum-pick" -> genEnumPick(args, random);
            case "ref-pick-random" -> genRefPickRandom(args, idsByConcept, random);
            default -> throw new IllegalArgumentException(
                    "Unknown $gen generator '" + generator + "' in token \"" + text + "\" -- expected one of: "
                            + "name, words, date-range, decimal-range, enum-pick, ref-pick-random");
        };
    }

    private String genName(Random random) {
        return GEN_FIRST_NAMES[random.nextInt(GEN_FIRST_NAMES.length)] + " "
                + GEN_LAST_NAMES[random.nextInt(GEN_LAST_NAMES.length)];
    }

    private String genWords(String args, Random random) {
        int count = 5;
        if (args != null && !args.isBlank()) {
            try {
                count = Integer.parseInt(args.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("$gen:words:" + args + " -- expected an integer word count");
            }
        }
        if (count < 1) {
            throw new IllegalArgumentException("$gen:words:" + args + " -- word count must be >= 1");
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(GEN_WORDS[random.nextInt(GEN_WORDS.length)]);
        }
        return result.toString();
    }

    private String genDateRange(String args, Random random) {
        String[] parts = requireGenArgs("date-range", args, 2);
        LocalDate min = parseGenDate("date-range", parts[0]);
        LocalDate max = parseGenDate("date-range", parts[1]);
        if (max.isBefore(min)) {
            throw new IllegalArgumentException("$gen:date-range:" + args + " -- max date is before min date");
        }
        int spanDays = (int) ChronoUnit.DAYS.between(min, max);
        int offset = spanDays == 0 ? 0 : random.nextInt(spanDays + 1);
        return min.plusDays(offset).toString();
    }

    private BigDecimal genDecimalRange(String args, Random random) {
        String[] parts = requireGenArgs("decimal-range", args, 2);
        BigDecimal min = parseGenDecimal("decimal-range", parts[0]);
        BigDecimal max = parseGenDecimal("decimal-range", parts[1]);
        if (max.compareTo(min) < 0) {
            throw new IllegalArgumentException("$gen:decimal-range:" + args + " -- max is less than min");
        }
        int scale = Math.max(Math.max(min.scale(), max.scale()), 2);
        BigDecimal span = max.subtract(min);
        BigDecimal value = min.add(span.multiply(BigDecimal.valueOf(random.nextDouble())));
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private String genEnumPick(String args, Random random) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("$gen:enum-pick requires a comma-separated list of values, e.g. $gen:enum-pick:PENDING,SHIPPED");
        }
        List<String> options = new ArrayList<>();
        for (String option : args.split(",")) {
            String trimmed = option.trim();
            if (!trimmed.isEmpty()) {
                options.add(trimmed);
            }
        }
        if (options.isEmpty()) {
            throw new IllegalArgumentException("$gen:enum-pick:" + args + " -- no non-blank values declared");
        }
        return options.get(random.nextInt(options.size()));
    }

    private String genRefPickRandom(String args, Map<String, List<String>> idsByConcept, Random random) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("$gen:ref-pick-random requires a concept name, e.g. $gen:ref-pick-random:Customer");
        }
        String concept = args.trim();
        List<String> pool = idsByConcept.get(concept);
        if (pool == null || pool.isEmpty()) {
            throw new IllegalArgumentException(
                    "$gen:ref-pick-random:" + concept + " -- no records of concept '" + concept
                            + "' have been created yet in this seed run. Seed records save in the order "
                            + "they are declared in the file's 'records' array -- declare '" + concept
                            + "'s records earlier in the file so they exist before this reference.");
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private static String[] requireGenArgs(String generator, String args, int expectedCount) {
        String[] parts = args == null ? new String[0] : args.split(":");
        if (parts.length != expectedCount) {
            throw new IllegalArgumentException(
                    "$gen:" + generator + (args == null ? "" : ":" + args) + " -- expected " + expectedCount
                            + " colon-separated argument(s), got " + parts.length);
        }
        return parts;
    }

    private static LocalDate parseGenDate(String generator, String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("$gen:" + generator + " -- '" + raw + "' is not an ISO date (yyyy-MM-dd)");
        }
    }

    private static BigDecimal parseGenDecimal(String generator, String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("$gen:" + generator + " -- '" + raw + "' is not a decimal number");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) objectMapper.convertValue(node, Object.class);
    }

    private static String requireConcept(JsonNode declared) {
        String concept = textOrNull(declared, "concept");
        if (concept == null) {
            throw new IllegalArgumentException("Seed record missing required 'concept'");
        }
        return concept;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }

    private record ExpandedRecord(String alias, String concept, String id, Map<String, Object> data) {
    }

    public static final class SeedNotFoundException extends RuntimeException {
        public SeedNotFoundException(String message) {
            super(message);
        }
    }

    public static final class SeedLoadException extends RuntimeException {
        public SeedLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record SeedRunResult(
            String seedId,
            String kind,
            Map<String, Integer> createdCounts,
            boolean ok,
            Integer failedRecordIndex,
            String failedAlias,
            String failedConcept,
            String failureMessage,
            long elapsedMs
    ) {
        static SeedRunResult success(String seedId, String kind, Map<String, Integer> createdCounts, long elapsedMs) {
            return new SeedRunResult(seedId, kind, createdCounts, true, null, null, null, null, elapsedMs);
        }

        static SeedRunResult failure(
                String seedId, String kind, Map<String, Integer> createdCounts,
                int failedRecordIndex, String failedAlias, String failedConcept, String failureMessage, long elapsedMs
        ) {
            return new SeedRunResult(seedId, kind, createdCounts, false, failedRecordIndex, failedAlias, failedConcept, failureMessage, elapsedMs);
        }
    }
}
