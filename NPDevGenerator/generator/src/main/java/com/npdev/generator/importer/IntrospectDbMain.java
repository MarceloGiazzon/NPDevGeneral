package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * {@code npdev import db} -- IMP-1. Introspects a JDBC database and writes {@code model.json} and
 * {@code import-report.json} into {@code --out}. Needs the database's JDBC driver on the classpath (the
 * CLI adds the staged H2 driver and any {@code --driver-jar}). Exit codes: 0 = written, 2 = could not.
 */
public final class IntrospectDbMain {

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private IntrospectDbMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = "";
        String schema = null;
        String namespace = "imported.app";
        String outDir = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            boolean hasValue = i + 1 < args.length;
            if ("--url".equals(arg) && hasValue) {
                url = args[++i];
            } else if ("--user".equals(arg) && hasValue) {
                user = args[++i];
            } else if ("--password".equals(arg) && hasValue) {
                password = args[++i];
            } else if ("--schema".equals(arg) && hasValue) {
                schema = args[++i];
            } else if ("--namespace".equals(arg) && hasValue) {
                namespace = args[++i];
            } else if ("--out".equals(arg) && hasValue) {
                outDir = args[++i];
            } else {
                err.println("unknown or incomplete argument: " + arg);
                usage(err);
                return 2;
            }
        }
        if (url == null || outDir == null) {
            usage(err);
            return 2;
        }
        try (Connection connection = user == null
                ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password)) {
            DbIntrospector.Result result = DbIntrospector.introspect(connection, schema, namespace, redact(url));
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            write(dir.resolve("model.json"), PRETTY.writeValueAsString(result.model()));
            write(dir.resolve("import-report.json"), PRETTY.writeValueAsString(result.report()));
            var counts = result.report().get("counts");
            out.println("npdev import db: " + counts.get("tables") + " table(s), " + counts.get("columnsMapped") + "/"
                    + counts.get("columns") + " column(s) mapped, " + counts.get("manualItems") + " manual item(s).");
            out.println("  model:  " + dir.resolve("model.json").toAbsolutePath());
            out.println("  report: " + dir.resolve("import-report.json").toAbsolutePath());
            return 0;
        } catch (SQLException | IOException exception) {
            err.println("npdev import db: " + exception.getMessage());
            return 2;
        }
    }

    private static void write(Path path, String json) throws IOException {
        Files.writeString(path, json + "\n", StandardCharsets.UTF_8);
    }

    /** The URL goes into the report; a password embedded in it must not. */
    static String redact(String url) {
        return url.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=***");
    }

    private static void usage(PrintStream err) {
        err.println("usage: IntrospectDbMain --url <jdbc-url> --out <dir> [--user u] [--password p] [--schema s] [--namespace n]");
    }
}
