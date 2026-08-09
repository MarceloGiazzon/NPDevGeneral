package com.npdev.kernel.storage.sql;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The reserved-word set per engine, read from {@code npdev/sql-reserved-words.properties}.
 *
 * <h2>Data, because the words genuinely differ</h2>
 *
 * <p>{@code rank} is reserved on MySQL and not on Postgres. {@code plan} is reserved on SQL Server
 * and nowhere else. A single shared list would either over-quote (changing emitted SQL on engines
 * that never needed it) or under-quote (leaving the defect in place on the one that did). So the
 * list is a DIALECT fact, and adding a word is editing data rather than code.
 *
 * <p>The file carries a {@code common} set -- the ~72 words every SQL engine reserves -- plus one
 * line per engine. A word is reserved for an engine if it is in either.
 *
 * <p><b>A .properties file rather than JSON</b>, because the kernel has no Jackson on its compile
 * classpath and deliberately stays that way -- the same reason it has zero JDBC imports. It is still
 * data: adding a word is editing a line.
 *
 * <p><b>Not the same list as {@code ReservedColumnNames}.</b> That one is NPDEV-reserved --
 * {@code version}, {@code row_version}, {@code tenant_id} are columns the platform adds to every
 * business table, and a model field colliding with one is refused with an actionable message at
 * generation time. This list is SQL-reserved: words the ENGINE will not accept unquoted. Different
 * question, different remedy (refuse vs quote), deliberately separate.
 */
public final class SqlReservedWords {

    private static final String RESOURCE = "npdev/sql-reserved-words.properties";
    private static final Map<String, Set<String>> BY_ENGINE = load();

    private SqlReservedWords() {
    }

    /**
     * True when {@code engineName} reserves {@code identifier}.
     *
     * <p>Case-insensitive: SQL keywords are, and a model field named {@code Order} collides exactly
     * as {@code order} does.
     *
     * <p>An engine with no entry reserves nothing rather than everything. That is deliberate and it
     * is the safe direction: an unknown engine keeps today's behaviour (no quoting) instead of
     * suddenly quoting every identifier in an app that used to work.
     */
    public static boolean isReserved(String engineName, String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        Set<String> words = BY_ENGINE.get(engineName == null ? "" : engineName.toLowerCase(Locale.ROOT));
        return words != null && words.contains(identifier.trim().toLowerCase(Locale.ROOT));
    }

    /** How many words this engine reserves -- for the conformance vector, so the set cannot go empty. */
    public static int countFor(String engineName) {
        Set<String> words = BY_ENGINE.get(engineName == null ? "" : engineName.toLowerCase(Locale.ROOT));
        return words == null ? 0 : words.size();
    }

    private static Map<String, Set<String>> load() {
        Properties properties = new Properties();
        try (InputStream stream = SqlReservedWords.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "reserved-word resource " + RESOURCE + " is missing from the kernel jar. Without "
                        + "it every identifier would be treated as unreserved, which is the STOR-6 "
                        + "defect restored silently -- refusing instead.");
            }
            properties.load(stream);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + RESOURCE, failure);
        }
        Set<String> common = split(properties.getProperty("common"));
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if ("common".equals(key)) {
                continue;
            }
            Set<String> words = new HashSet<>(common);
            words.addAll(split(properties.getProperty(key)));
            out.put(key.toLowerCase(Locale.ROOT), Set.copyOf(words));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    RESOURCE + " declared no engines. An empty reserved set passes every check while "
                    + "protecting nothing.");
        }
        return Map.copyOf(out);
    }

    private static Set<String> split(String csv) {
        Set<String> out = new HashSet<>();
        if (csv != null) {
            for (String word : csv.split(",")) {
                String trimmed = word.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }
}
