package com.finalexec.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OPS-10: online BACKUP TO into a directory with newest-N retention, against a real file H2. */
class ScheduledH2BackupTest {

    @TempDir
    Path tmp;

    private DriverManagerDataSource fileDataSource() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:file:" + tmp.resolve("dbfiles").resolve("db").toAbsolutePath());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(20))");
            statement.execute("INSERT INTO t VALUES (1, 'x')");
        }
        return dataSource;
    }

    private static List<String> zipNames(Path dir) throws Exception {
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).sorted().toList();
        }
    }

    @Test
    void keepsOnlyTheNewestNZipsAndTheZipHoldsTheDatabaseFile() throws Exception {
        DriverManagerDataSource dataSource = fileDataSource();
        Path backups = tmp.resolve("backups");
        Instant start = Instant.parse("2026-10-16T03:00:00Z");

        String lastName = null;
        for (int i = 0; i < 3; i++) {
            ScheduledH2Backup backup = new ScheduledH2Backup(dataSource, true, backups, 2, "db",
                    Clock.fixed(start.plusSeconds(i), ZoneOffset.UTC));
            lastName = backup.runNow().getFileName().toString();
        }

        List<String> names = zipNames(backups);
        assertEquals(2, names.size(), names.toString());
        assertEquals("db-20261016T030002Z.zip", lastName);
        assertTrue(names.contains(lastName), "newest kept: " + names);
        assertTrue(!names.contains("db-20261016T030000Z.zip"), "oldest pruned: " + names);

        try (ZipFile zip = new ZipFile(backups.resolve(lastName).toFile())) {
            assertTrue(zip.stream().anyMatch(e -> e.getName().endsWith(".mv.db")),
                    "backup zip must contain the .mv.db file");
        }
    }

    @Test
    void disabledWritesNothing() throws Exception {
        Path backups = tmp.resolve("off");
        new ScheduledH2Backup(fileDataSource(), false, backups, 2, "db", Clock.systemUTC()).scheduledRun();
        assertTrue(!Files.exists(backups), "disabled backup must not even create the directory");
    }

    @Test
    void nonH2EngineIsDisabledThroughThePublicConstructor() throws Exception {
        Path backups = tmp.resolve("pg");
        DriverManagerDataSource dataSource = fileDataSource();
        new ScheduledH2Backup(providerOf(dataSource), "Postgres", true, backups.toString(), 14, "db").scheduledRun();
        new ScheduledH2Backup(providerOf(dataSource), "InMemory", true, backups.toString(), 14, "db").scheduledRun();
        assertTrue(!Files.exists(backups));
    }

    @Test
    void h2LocalEngineThroughThePublicConstructorIsEnabled() throws Exception {
        Path backups = tmp.resolve("local");
        new ScheduledH2Backup(providerOf(fileDataSource()), "H2Local", true, backups.toString(), 14, "db").scheduledRun();
        assertEquals(1, zipNames(backups).size());
    }

    private static org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> providerOf(javax.sql.DataSource ds) {
        org.springframework.beans.factory.support.StaticListableBeanFactory factory =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        if (ds != null) {
            factory.addBean("ds", ds);
        }
        return factory.getBeanProvider(javax.sql.DataSource.class);
    }

    @Test
    void appWithoutADataSourceBeanStillConstructsAndDoesNothing() {
        new ScheduledH2Backup(providerOf(null), "InMemory", true, "unused", 14, "db").scheduledRun();
    }
}
