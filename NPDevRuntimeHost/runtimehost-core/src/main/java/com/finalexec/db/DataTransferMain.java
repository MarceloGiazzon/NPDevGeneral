package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finalexec.db.datamobility.DataExporter;
import com.finalexec.db.datamobility.DataImporter;
import com.finalexec.db.datamobility.DataMobilityStructureCheck;
import com.finalexec.db.datamobility.DataTransfer;
import com.finalexec.db.datamobility.StructureCheckResult;
import com.finalexec.db.datamobility.TableScopeResolver;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code npdev db export|import|structure-check} -- the CLI entry point for the data-mobility
 * feature, mirroring {@link SchemaVerifyMain}'s shape exactly: a plain JDBC connection (no pooling,
 * no Spring, no app boot), an engine auto-detected from the live connection via {@link
 * SqlDialects#forConnection} (never {@code .active()} -- this process has no app configuration to
 * read the engine from), and a {@code --json} flag for machine-readable output the Python CLI can
 * parse directly.
 *
 * <p>Covers all three data-mobility directions: {@code export}/{@code import} (DB&lt;-&gt;file) and
 * {@code transfer} (direct DB-to-DB, via {@link DataTransfer} -- no file touched at all), plus a
 * standalone {@code structure-check} between two live databases usable on its own.
 */
public final class DataTransferMain {

    static final int EXIT_OK = 0;
    static final int EXIT_BLOCKED_OR_NEEDS_CONFIRMATION = 1;
    static final int EXIT_COULD_NOT_DETERMINE = 2;

    private DataTransferMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err);
        }
        String subcommand = args[0];
        Args rest = Args.parse(args, 1);
        return switch (subcommand) {
            case "export" -> runExport(rest, out, err);
            case "import" -> runImport(rest, out, err);
            case "transfer" -> runTransfer(rest, out, err);
            case "structure-check" -> runStructureCheck(rest, out, err);
            default -> usage(err);
        };
    }

    private static int usage(PrintStream err) {
        err.println("usage: DataTransferMain export|import|transfer|structure-check [options]");
        err.println("  export --url <jdbcUrl> [--user] [--password] --format csv|sql --tables all|business|<comma-list> --out <dir> [--json]");
        err.println("  import --bundle <dir> --format csv|sql --url <jdbcUrl> [--user] [--password] [--include-ddl] [--confirm] [--json]");
        err.println("  transfer --source-url <jdbcUrl> [--source-user] [--source-password] --target-url <jdbcUrl> [--target-user] [--target-password] --tables all|business|<comma-list> [--include-ddl] [--confirm] [--json]");
        err.println("  structure-check --source-url <jdbcUrl> [--source-user] [--source-password] --target-url <jdbcUrl> [--target-user] [--target-password] [--include-ddl] --tables all|business|<comma-list> [--json]");
        return EXIT_COULD_NOT_DETERMINE;
    }

    // ------------------------------------------------------------------ export

    private static int runExport(Args a, PrintStream out, PrintStream err) {
        if (a.missing("url", "format", "tables", "out")) {
            return usage(err);
        }
        DataExporter.Format format = parseFormat(a.get("format"), err);
        if (format == null) {
            return EXIT_COULD_NOT_DETERMINE;
        }
        DataSource dataSource = new UrlDataSource(a.get("url"), a.get("user"), a.get("password"));
        SqlDialect dialect;
        try (Connection probe = dataSource.getConnection()) {
            dialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db export: could not connect to " + a.get("url") + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        Set<String> tableScope = resolveTableScope(a.get("tables"), err);
        if (tableScope == null) {
            return EXIT_COULD_NOT_DETERMINE;
        }
        try {
            DataExporter.ExportResult result = DataExporter.export(
                    dataSource, dialect.systemSchemas(), dialect.name(),
                    tableScope.isEmpty() ? null : tableScope, format, Path.of(a.get("out")));
            if (a.flag("json")) {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode root = mapper.createObjectNode();
                root.put("status", "ok");
                root.put("tableCount", result.schema().tables().size());
                ObjectNode counts = root.putObject("rowCountsByTable");
                result.rowCountsByTable().forEach(counts::put);
                out.println(mapper.writeValueAsString(root));
            } else {
                out.println("Exported " + result.schema().tables().size() + " table(s) to " + a.get("out"));
                result.rowCountsByTable().forEach((table, count) -> out.println("  " + table + ": " + count + " row(s)"));
            }
            return EXIT_OK;
        } catch (Exception failure) {
            err.println("npdev db export: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
    }

    // ------------------------------------------------------------------ import

    private static int runImport(Args a, PrintStream out, PrintStream err) {
        if (a.missing("bundle", "format", "url")) {
            return usage(err);
        }
        DataExporter.Format format = parseFormat(a.get("format"), err);
        if (format == null) {
            return EXIT_COULD_NOT_DETERMINE;
        }
        DataSource dataSource = new UrlDataSource(a.get("url"), a.get("user"), a.get("password"));
        SqlDialect dialect;
        try (Connection probe = dataSource.getConnection()) {
            dialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db import: could not connect to " + a.get("url") + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        boolean includeDdl = a.flag("include-ddl");
        boolean confirmed = a.flag("confirm");
        try {
            DataImporter.ImportResult result = DataImporter.importFrom(
                    Path.of(a.get("bundle")), format, dataSource, dialect.systemSchemas(), dialect.name(), includeDdl, confirmed);
            printImportResult(result, a.flag("json"), out);
            return result.outcome() == DataImporter.Outcome.IMPORTED ? EXIT_OK : EXIT_BLOCKED_OR_NEEDS_CONFIRMATION;
        } catch (Exception failure) {
            err.println("npdev db import: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
    }

    // ------------------------------------------------------------------ transfer (direct DB-to-DB)

    private static int runTransfer(Args a, PrintStream out, PrintStream err) {
        if (a.missing("source-url", "target-url", "tables")) {
            return usage(err);
        }
        DataSource sourceDs = new UrlDataSource(a.get("source-url"), a.get("source-user"), a.get("source-password"));
        DataSource targetDs = new UrlDataSource(a.get("target-url"), a.get("target-user"), a.get("target-password"));
        SqlDialect sourceDialect;
        SqlDialect targetDialect;
        try (Connection probe = sourceDs.getConnection()) {
            sourceDialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db transfer: could not connect to source " + a.get("source-url") + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        try (Connection probe = targetDs.getConnection()) {
            targetDialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db transfer: could not connect to target " + a.get("target-url") + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        Set<String> tableScope = resolveTableScope(a.get("tables"), err);
        if (tableScope == null) {
            return EXIT_COULD_NOT_DETERMINE;
        }
        boolean includeDdl = a.flag("include-ddl");
        boolean confirmed = a.flag("confirm");
        try {
            DataTransfer.TransferResult result = DataTransfer.transfer(
                    sourceDs, sourceDialect.systemSchemas(), sourceDialect.name(), tableScope,
                    targetDs, targetDialect.systemSchemas(), targetDialect.name(), includeDdl, confirmed);
            printTransferResult(result, a.flag("json"), out);
            return result.outcome() == DataTransfer.Outcome.TRANSFERRED ? EXIT_OK : EXIT_BLOCKED_OR_NEEDS_CONFIRMATION;
        } catch (Exception failure) {
            err.println("npdev db transfer: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
    }

    private static void printTransferResult(DataTransfer.TransferResult result, boolean json, PrintStream out) throws Exception {
        if (json) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("outcome", result.outcome().name());
            root.put("verdict", result.structureCheck().verdict().name());
            result.structureCheck().incompatibleReasons().forEach(root.putArray("incompatibleReasons")::add);
            result.structureCheck().compatibleReasons().forEach(root.putArray("compatibleReasons")::add);
            ObjectNode counts = root.putObject("rowCountsByTable");
            result.rowCountsByTable().forEach(counts::put);
            out.println(mapper.writeValueAsString(root));
            return;
        }
        out.println("Outcome: " + result.outcome() + " (structure check: " + result.structureCheck().verdict() + ")");
        result.structureCheck().incompatibleReasons().forEach(r -> out.println("  BLOCKED: " + r));
        result.structureCheck().compatibleReasons().forEach(r -> out.println("  NOTE: " + r));
        if (result.outcome() == DataTransfer.Outcome.NEEDS_CONFIRMATION) {
            out.println("Re-run with --confirm to proceed.");
        }
        result.rowCountsByTable().forEach((table, count) -> out.println("  " + table + ": " + count + " row(s) transferred"));
    }

    // ------------------------------------------------------------------ structure-check

    private static int runStructureCheck(Args a, PrintStream out, PrintStream err) {
        if (a.missing("source-url", "target-url", "tables")) {
            return usage(err);
        }
        DataSource sourceDs = new UrlDataSource(a.get("source-url"), a.get("source-user"), a.get("source-password"));
        DataSource targetDs = new UrlDataSource(a.get("target-url"), a.get("target-user"), a.get("target-password"));
        SqlDialect sourceDialect;
        SqlDialect targetDialect;
        try (Connection probe = sourceDs.getConnection()) {
            sourceDialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db structure-check: could not connect to source " + a.get("source-url") + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        try (Connection probe = targetDs.getConnection()) {
            targetDialect = SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            err.println("npdev db structure-check: could not connect to target " + a.get("target-url") + ": " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
        Set<String> tableScope = resolveTableScope(a.get("tables"), err);
        if (tableScope == null) {
            return EXIT_COULD_NOT_DETERMINE;
        }
        boolean includeDdl = a.flag("include-ddl");
        try {
            CurrentSchema sourceSchema = new CurrentSchemaReader().read(sourceDs, sourceDialect.systemSchemas());
            CurrentSchema sourceScoped = tableScope.isEmpty() ? sourceSchema : DataExporter.scope(sourceSchema, tableScope);
            CurrentSchema targetSchema = new CurrentSchemaReader().read(targetDs, targetDialect.systemSchemas());
            StructureCheckResult result = DataMobilityStructureCheck.check(sourceScoped, targetSchema, targetDialect.name(), includeDdl);
            printStructureCheckResult(result, a.flag("json"), out);
            return result.verdict().name().equals("INCOMPATIBLE") ? EXIT_BLOCKED_OR_NEEDS_CONFIRMATION : EXIT_OK;
        } catch (Exception failure) {
            err.println("npdev db structure-check: " + failure.getMessage());
            return EXIT_COULD_NOT_DETERMINE;
        }
    }

    // ------------------------------------------------------------------ shared helpers

    /** @return lower-cased table names, empty for "all", or {@code null} on an unresolvable manifest */
    private static Set<String> resolveTableScope(String tablesArg, PrintStream err) {
        if ("all".equalsIgnoreCase(tablesArg)) {
            return Set.of();
        }
        if ("business".equalsIgnoreCase(tablesArg)) {
            Set<String> resolved = new TableScopeResolver().resolve(TableScopeResolver.Scope.BUSINESS, null);
            if (resolved.isEmpty()) {
                err.println("npdev db: --tables business resolved to zero tables -- is npdev/metadata/concepts.manifest.json on the classpath?");
            }
            return resolved;
        }
        Set<String> explicit = new java.util.LinkedHashSet<>();
        for (String t : tablesArg.split(",")) {
            if (!t.isBlank()) {
                explicit.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new TableScopeResolver().resolve(TableScopeResolver.Scope.EXPLICIT, explicit);
    }

    private static DataExporter.Format parseFormat(String value, PrintStream err) {
        if ("csv".equalsIgnoreCase(value)) {
            return DataExporter.Format.CSV;
        }
        if ("sql".equalsIgnoreCase(value)) {
            return DataExporter.Format.SQL;
        }
        err.println("npdev db: --format must be 'csv' or 'sql', got '" + value + "'");
        return null;
    }

    private static void printImportResult(DataImporter.ImportResult result, boolean json, PrintStream out) throws Exception {
        if (json) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("outcome", result.outcome().name());
            root.put("verdict", result.structureCheck().verdict().name());
            result.structureCheck().incompatibleReasons().forEach(root.putArray("incompatibleReasons")::add);
            result.structureCheck().compatibleReasons().forEach(root.putArray("compatibleReasons")::add);
            ObjectNode counts = root.putObject("rowCountsByTable");
            result.rowCountsByTable().forEach(counts::put);
            out.println(mapper.writeValueAsString(root));
            return;
        }
        out.println("Outcome: " + result.outcome() + " (structure check: " + result.structureCheck().verdict() + ")");
        result.structureCheck().incompatibleReasons().forEach(r -> out.println("  BLOCKED: " + r));
        result.structureCheck().compatibleReasons().forEach(r -> out.println("  NOTE: " + r));
        if (result.outcome() == DataImporter.Outcome.NEEDS_CONFIRMATION) {
            out.println("Re-run with --confirm to proceed.");
        }
        result.rowCountsByTable().forEach((table, count) -> out.println("  " + table + ": " + count + " row(s) imported"));
    }

    private static void printStructureCheckResult(StructureCheckResult result, boolean json, PrintStream out) throws Exception {
        if (json) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("verdict", result.verdict().name());
            result.incompatibleReasons().forEach(root.putArray("incompatibleReasons")::add);
            result.compatibleReasons().forEach(root.putArray("compatibleReasons")::add);
            out.println(mapper.writeValueAsString(root));
            return;
        }
        out.println("Verdict: " + result.verdict());
        result.incompatibleReasons().forEach(r -> out.println("  INCOMPATIBLE: " + r));
        result.compatibleReasons().forEach(r -> out.println("  COMPATIBLE: " + r));
    }

    /** Simple {@code --flag value} / {@code --flag} (boolean) argv parser -- no external dependency,
     *  matching {@link SchemaVerifyMain}'s own hand-rolled loop rather than pulling in a CLI library
     *  for a handful of options. */
    private static final class Args {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();
        private final java.util.Set<String> flags = new java.util.HashSet<>();

        private static final java.util.Set<String> BOOLEAN_FLAGS = Set.of("json", "include-ddl", "confirm");

        static Args parse(String[] args, int from) {
            Args parsed = new Args();
            for (int i = from; i < args.length; i++) {
                String raw = args[i];
                if (!raw.startsWith("--")) {
                    continue;
                }
                String name = raw.substring(2);
                if (BOOLEAN_FLAGS.contains(name)) {
                    parsed.flags.add(name);
                } else if (i + 1 < args.length) {
                    parsed.values.put(name, args[++i]);
                }
            }
            return parsed;
        }

        String get(String name) {
            return values.get(name);
        }

        boolean flag(String name) {
            return flags.contains(name);
        }

        boolean missing(String... names) {
            for (String name : names) {
                if (!values.containsKey(name) || values.get(name).isBlank()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Minimal {@link DataSource} over a single JDBC URL -- copied from {@link SchemaVerifyMain}'s
     *  own private helper rather than shared, since that class is on the live migration-refusal
     *  path and this deliberately avoids touching it for an unrelated feature. */
    private static final class UrlDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        private UrlDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return (user == null && password == null)
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pass) throws SQLException {
            return DriverManager.getConnection(url, username, pass);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
