package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Persists an {@link ImpactReport} for operator review (schema-engine rebuild, P6.3, Surface 1): writes the
 * JSON report to {@code runtime-data/impact-reports/<ts>-<from>-<to>.json} (retaining the last
 * {@value #MAX_RETAINED}) and prints the {@link ImpactReportText} table to stdout. Mirrors
 * {@code SchemaDropSnapshotWriter}'s retention pattern.
 *
 * <p><b>Never throws.</b> This is a read-only, best-effort diagnostic on the live migration path — every
 * failure (probe, IO, prune) is swallowed so it can never affect the boot or the acknowledgment flow.
 */
final class ImpactReportWriter {

    private static final Path REPORT_BASE = Paths.get("runtime-data", "impact-reports");
    private static final int MAX_RETAINED = 10;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private ImpactReportWriter() {
    }

    /**
     * Compute + persist + print the impact report for an upgrade, and return the rendered text (or
     * {@code null} on any failure) so a caller can reuse it (e.g. in a refusal message). {@code ackToken}
     * is the expected acknowledgment token (shown only when the verdict is DESTRUCTIVE). Fully swallowed —
     * never throws, never affects the boot.
     */
    static String writeAndPrint(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest,
            String fromFingerprint, String ackToken) {
        try {
            CurrentSchema current = new CurrentSchemaReader().read(dataSource);
            SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                    ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
            ImpactReport report = ImpactReport.generate(diff, dataSource);

            String generatedAt = Instant.now().toString();
            String toFingerprint = manifest.schemaFingerprint();
            String json = ImpactReportJson.render(report, generatedAt, fromFingerprint, toFingerprint, ackToken);
            String text = ImpactReportText.render(report, fromFingerprint, toFingerprint, ackToken);

            System.out.println(text);
            persist(json, fromFingerprint, toFingerprint);
            return text;
        } catch (Throwable ignored) {
            // best-effort diagnostic only — must never affect the boot
            return null;
        }
    }

    private static void persist(String json, String fromFingerprint, String toFingerprint) {
        try {
            Files.createDirectories(REPORT_BASE);
            String name = TIMESTAMP.format(Instant.now().atZone(ZoneOffset.UTC))
                    + "-" + shortFp(fromFingerprint) + "-" + shortFp(toFingerprint) + ".json";
            Path file = REPORT_BASE.resolve(name);
            Files.writeString(file, json, StandardCharsets.UTF_8);
            System.out.println("NPDev schema lifecycle: impact report written to " + file.toAbsolutePath());
            prune();
        } catch (IOException exception) {
            System.out.println("NPDev schema lifecycle: failed writing impact report: " + exception.getMessage());
        }
    }

    /** A filesystem-safe short form of a fingerprint (e.g. {@code sha256:abcd...} -> {@code abcd}). */
    private static String shortFp(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return "none";
        }
        String tail = fingerprint.contains(":") ? fingerprint.substring(fingerprint.indexOf(':') + 1) : fingerprint;
        String cleaned = tail.replaceAll("[^A-Za-z0-9]", "");
        return cleaned.isEmpty() ? "none" : cleaned.substring(0, Math.min(12, cleaned.length()));
    }

    private static void prune() {
        try {
            if (!Files.isDirectory(REPORT_BASE)) {
                return;
            }
            List<Path> reports = new ArrayList<>();
            try (var stream = Files.list(REPORT_BASE)) {
                stream.filter(Files::isRegularFile).forEach(reports::add);
            }
            reports.sort(Comparator.comparing(Path::getFileName).reversed());
            for (int index = MAX_RETAINED; index < reports.size(); index++) {
                try {
                    Files.deleteIfExists(reports.get(index));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            System.out.println("NPDev schema lifecycle: failed pruning old impact reports: " + exception.getMessage());
        }
    }
}
