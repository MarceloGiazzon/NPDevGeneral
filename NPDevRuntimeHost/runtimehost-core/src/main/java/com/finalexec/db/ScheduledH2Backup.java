package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OPS-10: nightly online backup for the embedded engines (H2Local / H2Server), which have no
 * database container for the compose `backup` sidecar to dump. Runs H2's own `BACKUP TO` (a
 * consistent online copy -- no downtime) into data/backups (data/ is regeneration-spared) and keeps
 * the newest N zips. Server engines (Postgres/MySQL) keep using deploy/backup-sidecar.sh.
 *
 * Off when npdev.backup.enabled=false or the engine is not H2Local/H2Server.
 */
@Component
public class ScheduledH2Backup {

    private static final Logger LOG = Logger.getLogger(ScheduledH2Backup.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final DataSource dataSource;
    private final boolean enabled;
    private final Path backupDir;
    private final int retentionCount;
    private final String filePrefix;
    private final Clock clock;

    @Autowired
    public ScheduledH2Backup(
            ObjectProvider<DataSource> dataSource,
            @Value("${npdev.database.engine:InMemory}") String engine,
            @Value("${npdev.backup.enabled:true}") boolean enabledFlag,
            @Value("${npdev.backup.dir:data/backups}") String backupDir,
            @Value("${npdev.backup.retention-count:14}") int retentionCount,
            @Value("${npdev.backup.file-prefix:db}") String filePrefix) {
        // InMemory (and other DataSource-less) apps have no DataSource bean: resolve it lazily so they still boot.
        this(dataSource.getIfAvailable(),
                enabledFlag && ("H2Local".equalsIgnoreCase(engine) || "H2Server".equalsIgnoreCase(engine))
                        && dataSource.getIfAvailable() != null,
                Path.of(backupDir), Math.max(1, retentionCount), filePrefix, Clock.systemUTC());
    }

    // Package-visible for tests.
    ScheduledH2Backup(DataSource dataSource, boolean enabled, Path backupDir, int retentionCount,
                      String filePrefix, Clock clock) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.backupDir = backupDir;
        this.retentionCount = retentionCount;
        this.filePrefix = filePrefix == null || filePrefix.isBlank() ? "app" : filePrefix.replaceAll("[^A-Za-z0-9._-]", "_");
        this.clock = clock;
    }

    @Scheduled(cron = "${npdev.backup.cron:0 0 3 * * *}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            Path written = runNow();
            LOG.info(() -> "OPS-10: database backup written to " + written.toAbsolutePath());
        } catch (Exception failure) {
            // Never crash the scheduler thread; OPS-12's alert digest can pick this up from the log/metric.
            LOG.log(Level.SEVERE, "OPS-10: scheduled database backup FAILED", failure);
        }
    }

    /** One backup + retention pruning. Returns the zip written. */
    public Path runNow() throws Exception {
        Files.createDirectories(backupDir);
        Path target = backupDir.resolve(filePrefix + "-" + STAMP.format(clock.instant()) + ".zip").toAbsolutePath();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(H2Dialect.onlineBackupStatement(target.toString()));
        }
        prune();
        return target;
    }

    void prune() throws IOException {
        List<Path> zips;
        try (Stream<Path> listing = Files.list(backupDir)) {
            zips = listing.filter(p -> p.getFileName().toString().startsWith(filePrefix + "-")
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (int i = retentionCount; i < zips.size(); i++) {
            Files.deleteIfExists(zips.get(i));
        }
    }
}
