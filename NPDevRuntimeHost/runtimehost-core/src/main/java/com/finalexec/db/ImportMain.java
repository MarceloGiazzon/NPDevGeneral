package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code npdev db import} -- the file-to-database half of the data mobility tool (see
 * {@link ExportMain}). For {@code --format csv} (the default), every table is gated by
 * {@link ImportStructureVerdict}'s pre-import "DB Structure Check" against the {@code manifest.json}
 * the matching export wrote. For {@code --format sql-insert}, the exported files are plain SQL
 * scripts and there is no structure to check -- they either execute or they don't.
 *
 * <h2>CSV import gating</h2>
 * EQUAL always proceeds under {@code --apply}. COMPATIBLE additionally requires {@code --force} --
 * the shapes are not identical, so this tool asks for an explicit "yes, I mean it" even though the
 * mismatch is one this tool judges safe. INCOMPATIBLE always blocks, {@code --force} or not.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>{@code 0} -- check/import completed with nothing incompatible (and, with {@code --apply},
 *       every table wrote cleanly).</li>
 *   <li>{@code 1} -- at least one table is INCOMPATIBLE, or (with {@code --apply}) a write failed.</li>
 *   <li>{@code 2} -- bad arguments, or could not connect / read the input directory.</li>
 * </ul>
 */
public final class ImportMain {

    static final int EXIT_OK = 0;
    static final int EXIT_NEEDS_ATTENTION = 1;
    static final int EXIT_BAD_ARGS = 2;

    private ImportMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String url = null;
        String user = null;
        String password = null;
        String inDir = null;
        String format = "csv";
        boolean apply = false;
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(arg) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--password".equals(arg) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--in".equals(arg) && i + 1 < args.length) {
                inDir = args[++i];
            } else if ("--format".equals(arg) && i + 1 < args.length) {
                format = args[++i];
            } else if ("--apply".equals(arg)) {
                apply = true;
            } else if ("--force".equals(arg)) {
                force = true;
            } else {
                err.println("npdev db import: unrecognized or incomplete argument '" + arg + "'");
                printUsage(err);
                return EXIT_BAD_ARGS;
            }
        }
        if (url == null || url.isBlank() || inDir == null || inDir.isBlank()) {
            printUsage(err);
            return EXIT_BAD_ARGS;
        }
        Path directory = Path.of(inDir);
        if (!Files.isDirectory(directory)) {
            err.println("npdev db import: not a directory: " + directory);
            return EXIT_BAD_ARGS;
        }
        if (!"csv".equals(format) && !"sql-insert".equals(format)) {
            err.println("npdev db import: unknown --format '" + format + "' (expected csv or sql-insert)");
            return EXIT_BAD_ARGS;
        }

        DataSource target = new DataTransferUrlDataSource(url, user, password);
        try (Connection connection = target.getConnection()) {
            if ("sql-insert".equals(format)) {
                return runSqlInsertImport(connection, directory, apply, out, err);
            }
            return runCsvImport(connection, directory, apply, force, out, err);
        } catch (SQLException | IOException failure) {
            err.println("npdev db import: FAILED -- " + failure.getMessage());
            return EXIT_NEEDS_ATTENTION;
        }
    }

    private static int runCsvImport(Connection connection, Path directory, boolean apply, boolean force,
            PrintStream out, PrintStream err) throws SQLException, IOException {
        DataTransferManifest.Manifest manifest = DataTransferManifest.read(directory);
        // Pin active, not just detect: SchemaLifecycleExecutor.quotedIdentifier (used by
        // CsvRowSerializer's INSERT text) reads SqlDialects.active(), not the connection it quotes for.
        SqlDialect targetDialect = SqlDialects.forConnection(connection);
        SqlDialects.setActive(targetDialect);
        RowSerializer serializer = new CsvRowSerializer();
        boolean anyIncompatible = false;
        boolean anyWriteMismatch = false;
        for (DataTransferManifest.TableManifest table : manifest.tables()) {
            List<DataTransferManifest.ColumnMeta> targetColumns =
                    DataTransferIntrospection.listColumns(connection, targetDialect, table.table());
            ImportStructureVerdict.TableVerdict verdict =
                    ImportStructureVerdict.compare(table.table(), table.columns(), targetColumns);
            out.println("  " + table.table() + ": " + verdict.verdict() + " (" + table.rowCount() + " source row(s))");
            for (ImportStructureVerdict.ColumnProblem problem : verdict.problems()) {
                out.println("    " + problem.column() + ": " + problem.reason());
            }
            if (verdict.verdict() == ImportStructureVerdict.Verdict.INCOMPATIBLE) {
                anyIncompatible = true;
                continue;
            }
            if (!apply) {
                if (verdict.verdict() == ImportStructureVerdict.Verdict.COMPATIBLE) {
                    out.println("    (COMPATIBLE, not EQUAL -- importing this table will additionally require --force)");
                }
                continue;
            }
            boolean allowed = verdict.verdict() == ImportStructureVerdict.Verdict.EQUAL || force;
            if (!allowed) {
                out.println("    SKIPPED -- COMPATIBLE but not EQUAL, and --force was not given.");
                continue;
            }
            Path inFile = directory.resolve(table.table() + ".csv");
            long inserted = serializer.importTable(connection, table.table(), table.columns(), inFile);
            out.println("    imported " + inserted + " row(s).");
            if (inserted != table.rowCount()) {
                anyWriteMismatch = true;
                out.println("    WARNING: source manifest recorded " + table.rowCount() + " row(s) at export time.");
            }
        }
        return (anyIncompatible || anyWriteMismatch) ? EXIT_NEEDS_ATTENTION : EXIT_OK;
    }

    private static int runSqlInsertImport(Connection connection, Path directory, boolean apply, PrintStream out, PrintStream err)
            throws IOException {
        RowSerializer serializer = new SqlInsertRowSerializer();
        boolean anyFailed = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.sql")) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String table = fileName.substring(0, fileName.length() - ".sql".length());
                if (!apply) {
                    long statementCount;
                    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                        statementCount = lines.filter(line -> !line.isBlank()).count();
                    }
                    out.println("  " + table + ": would run " + statementCount + " statement(s) (dry-run).");
                    continue;
                }
                try {
                    long executed = serializer.importTable(connection, table, List.of(), file);
                    out.println("  " + table + ": ran " + executed + " statement(s).");
                } catch (SQLException failure) {
                    anyFailed = true;
                    err.println("  " + table + ": FAILED -- " + failure.getMessage());
                }
            }
        }
        return anyFailed ? EXIT_NEEDS_ATTENTION : EXIT_OK;
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: ImportMain --url <jdbcUrl> [--user <user>] [--password <password>] "
                + "--in <directory> [--format csv|sql-insert] [--apply] [--force]");
    }
}
