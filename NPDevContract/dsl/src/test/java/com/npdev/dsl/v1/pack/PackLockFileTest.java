package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackLockFileTest {

    @TempDir
    Path temp;

    @Test
    void writeThenReadRoundTrips() throws Exception {
        PackLockFile lock = PackLockFile.of(Map.of(
                "user", new PackLockFile.LockedPack("2.5.0", "sha256:abc123", "packs/user/pack.json")));
        lock.write(temp);

        assertTrue(PackLockFile.exists(temp));
        PackLockFile reread = PackLockFile.read(temp);
        assertEquals(1, reread.packs().size());
        PackLockFile.LockedPack locked = reread.packs().get("user");
        assertEquals("2.5.0", locked.resolvedVersion());
        assertEquals("sha256:abc123", locked.digest());
        assertEquals("packs/user/pack.json", locked.sourcePath());
    }

    @Test
    void writeIsDeterministicRegardlessOfInputMapOrder() throws Exception {
        PackLockFile.of(new java.util.LinkedHashMap<>(Map.of(
                "billing", new PackLockFile.LockedPack("1.0.0", "sha256:b", "packs/billing/pack.json"),
                "user", new PackLockFile.LockedPack("2.5.0", "sha256:u", "packs/user/pack.json")
        ))).write(temp);
        String first = Files.readString(temp.resolve(PackLockFile.FILE_NAME));

        Path temp2 = Files.createTempDirectory("npdev-lock-order-");
        PackLockFile.of(new java.util.LinkedHashMap<>(Map.of(
                "user", new PackLockFile.LockedPack("2.5.0", "sha256:u", "packs/user/pack.json"),
                "billing", new PackLockFile.LockedPack("1.0.0", "sha256:b", "packs/billing/pack.json")
        ))).write(temp2);
        String second = Files.readString(temp2.resolve(PackLockFile.FILE_NAME));

        assertEquals(first, second, "lock file content must not depend on Map insertion order");
    }

    @Test
    void missingLockDoesNotExist() {
        assertFalse(PackLockFile.exists(temp));
    }

    @Test
    void threeArgConstructorDefaultsMigratedVersionToEmpty() {
        var locked = new PackLockFile.LockedPack("2.5.0", "sha256:abc123", "packs/user/pack.json");
        assertEquals("", locked.migratedVersion());
    }

    @Test
    void migratedVersionRoundTrips() throws Exception {
        PackLockFile lock = PackLockFile.of(Map.of(
                "user", new PackLockFile.LockedPack("3.0.0", "sha256:abc123", "packs/user/pack.json", "1.0.0")));
        lock.write(temp);

        PackLockFile reread = PackLockFile.read(temp);
        assertEquals("1.0.0", reread.packs().get("user").migratedVersion());
    }

    @Test
    void absentMigratedVersionDoesNotAppearInTheWrittenFile() throws Exception {
        PackLockFile.of(Map.of(
                "user", new PackLockFile.LockedPack("2.5.0", "sha256:abc123", "packs/user/pack.json")))
                .write(temp);
        String content = Files.readString(temp.resolve(PackLockFile.FILE_NAME));
        assertFalse(content.contains("migratedVersion"),
                "a lock entry with no migratedVersion must round-trip byte-identical to before this field existed");
    }

    @Test
    void readingALockWrittenBeforeMigratedVersionExistedDefaultsToEmpty() throws Exception {
        Files.writeString(temp.resolve(PackLockFile.FILE_NAME), """
                {
                  "schemaVersion": "npdev-lock.v1",
                  "packs": {
                    "user": { "resolvedVersion": "2.5.0", "digest": "sha256:abc123", "sourcePath": "packs/user/pack.json" }
                  }
                }
                """);
        PackLockFile reread = PackLockFile.read(temp);
        assertEquals("", reread.packs().get("user").migratedVersion());
    }

    @Test
    void sha256IsStableForTheSameBytes() throws Exception {
        Path file = temp.resolve("pack.json");
        Files.writeString(file, "{\"pack\":\"user\"}");
        String first = PackLockFile.sha256(file);
        String second = PackLockFile.sha256(file);
        assertEquals(first, second);
        assertTrue(first.startsWith("sha256:"));

        Files.writeString(file, "{\"pack\":\"user\",\"changed\":true}");
        String afterEdit = PackLockFile.sha256(file);
        assertFalse(first.equals(afterEdit), "digest must change when the file's bytes change");
    }
}
