package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code npdev db export} -- the data-mobility tool's DB-to-file half (ListaSementes.txt: "Data
 * mobility is very important value... I want to have some options... to export and import").
 *
 * <p>Reads the SOURCE database directly via JDBC and {@link SqlDialect}'s engine-agnostic
 * introspection ({@link DataTransferIntrospection}). Unlike {@link PromoteMain}/{@link SchemaVerifyMain},
 * it needs no {@code SchemaLifecycleExecutor.loadManifest()} classpath resource, so it works against
 * any JDBC-reachable database, NPDev-generated or not.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} -- export completed, every requested table written.</li>
 *   <li>{@code 1} -- could not connect, or a table failed mid-export (itemized on stdout/stderr).</li>
 *   <li>{@code 2} -- bad arguments.</li>
 * </ul>
 */
public final class ExportMain {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_BAD_ARGS = 2;

    private ExportMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        String outDir = null;
        String format = "csv";
        String scopeArg = "business";
        String tablesArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--out".equals(arg) && i + 1 < args.length) {
                outDir = args[++i];
            } else if ("--format".equals(arg) && i + 1 < args.length) {
                format = args[++i];
            } else if ("--scope".equals(arg) && i + 1 < args.length) {
                scopeArg = args[++i];
            } else if ("--tables".equals(arg) && i + 1 < args.length) {
                tablesArg = args[++i];
            } else {
                err.println("npdev db export: unrecognized or incomplete argument '" + arg + "'");
                printUsage(err);
                return EXIT_BAD_ARGS;
            }
        }
        if (url == null || url.isBlank() || outDir == null || outDir.isBlank()) {
            printUsage(err);
            return EXIT_BAD_ARGS;
        }
        RowSerializer serializer = serializerFor(format);
        if (serializer == null) {
            err.println("npdev db export: unknown --format '" + format + "' (expected csv or sql-insert)");
            return EXIT_BAD_ARGS;
        }
        DataTransferIntrospection.Scope scope;
        try {
            scope = DataTransferIntrospection.Scope.valueOf(scopeArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            err.println("npdev db export: unknown --scope '" + scopeArg + "' (expected all or business)");
            return EXIT_BAD_ARGS;
        }
        List<String> explicitTables = tablesArg == null || tablesArg.isBlank()
                ? List.of() : List.of(tablesArg.split(","));

        DataSource source = new DataTransferUrlDataSource(url, user, password);
        try (Connection connection = source.getConnection()) {
            // SqlDialects.forConnection, not .active(): this process has no app configuration to read
            // the engine from -- same reasoning as every sibling *Main class (see PromoteMain). Pinning
            // it as active matters because SchemaLifecycleExecutor.quotedIdentifier (used below, via
            // the RowSerializers) reads SqlDialects.active(), not the connection it is quoting for.
            SqlDialect dialect = SqlDialects.forConnection(connection);
            SqlDialects.setActive(dialect);
            List<String> tables = DataTransferIntrospection.resolveScope(connection, dialect, scope, explicitTables);
            if (tables.isEmpty()) {
                out.println("npdev db export: no tables matched (scope=" + scopeArg + ") -- nothing to export.");
                return EXIT_OK;
            }
            Path directory = Path.of(outDir);
            List<DataTransferManifest.TableManifest> manifestTables = new ArrayList<>();
            boolean failed = false;
            for (String table : tables) {
                List<DataTransferManifest.ColumnMeta> columns = DataTransferIntrospection.listColumns(connection, dialect, table);
                if (columns.isEmpty()) {
                    err.println("npdev db export: table '" + table + "' has no columns (does it exist?) -- skipped.");
                    failed = true;
                    continue;
                }
                String extension = "csv".equals(serializer.format()) ? ".csv" : ".sql";
                Path outFile = directory.resolve(table + extension);
                serializer.exportTable(connection, table, columns, outFile);
                long rowCount = countRows(connection, table);
                manifestTables.add(new DataTransferManifest.TableManifest(table, rowCount, columns));
                out.println("  " + table + ": " + rowCount + " row(s) -> " + outFile);
            }
            DataTransferManifest.write(directory,
                    new DataTransferManifest.Manifest(serializer.format(), url, manifestTables));
            out.println("npdev db export: wrote " + manifestTables.size() + " table(s) to " + directory
                    + " (format=" + serializer.format() + ", scope=" + scopeArg + ")");
            return failed ? EXIT_FAILED : EXIT_OK;
        } catch (SQLException | IOException failure) {
            err.println("npdev db export: FAILED -- " + failure.getMessage());
            return EXIT_FAILED;
        }
    }

    private static long countRows(Connection connection, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + SchemaLifecycleExecutor.quotedIdentifier(table);
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static RowSerializer serializerFor(String format) {
        return switch (format) {
            case "csv" -> new CsvRowSerializer();
            case "sql-insert" -> new SqlInsertRowSerializer();
            default -> null;
        };
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: ExportMain --url <jdbcUrl> [--user <user>] [--password <password>] "
                + "--out <directory> [--format csv|sql-insert] [--scope all|business] [--tables t1,t2]");
    }
}
