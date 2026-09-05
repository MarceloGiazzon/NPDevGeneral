package com.finalexec.db;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * B31: cross-process proof harness for {@link H2LocalBootLock} -- see
 * {@code H2LocalBootLockCrossProcessTest}, which spawns this as a REAL second {@code java} process
 * against the same H2Local file a first instance of this same class already holds. Two modes:
 *
 * <ul>
 *   <li>{@code hold <jdbcUrl> <releaseSignalFile>} -- acquires the lock, prints
 *       {@code HARNESS: ACQUIRED}, polls for {@code releaseSignalFile} to appear, then releases and
 *       prints {@code HARNESS: RELEASED}.</li>
 *   <li>{@code contend <jdbcUrl> <waitSecondsOverride>} -- sets the wait budget, attempts the same
 *       acquire; prints {@code HARNESS: ACQUIRED} on success or {@code HARNESS: FAILED <message>} and
 *       exits 4 on a genuine timeout.</li>
 * </ul>
 */
final class H2LocalBootLockHarness {

    private H2LocalBootLockHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: <hold|contend> <jdbcUrl> <releaseSignalFile|waitSecondsOverride>");
        }
        String mode = args[0];
        String jdbcUrl = args[1];
        String third = args[2];
        switch (mode) {
            case "hold" -> runHold(jdbcUrl, third);
            case "contend" -> runContend(jdbcUrl, third);
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void runHold(String jdbcUrl, String releaseSignalFile) throws Exception {
        H2LocalBootLock.Held held = H2LocalBootLock.acquireIfNeeded("H2Local", jdbcUrl).orElseThrow();
        System.out.println("HARNESS: ACQUIRED");
        System.out.flush();
        Path signal = Path.of(releaseSignalFile);
        while (!Files.exists(signal)) {
            Thread.sleep(50L);
        }
        H2LocalBootLock.release(held);
        System.out.println("HARNESS: RELEASED");
        System.out.flush();
    }

    private static void runContend(String jdbcUrl, String waitSecondsOverride) {
        System.setProperty("npdev.h2local.bootLock.waitSeconds", waitSecondsOverride);
        try {
            H2LocalBootLock.Held held = H2LocalBootLock.acquireIfNeeded("H2Local", jdbcUrl).orElseThrow();
            System.out.println("HARNESS: ACQUIRED");
            System.out.flush();
            H2LocalBootLock.release(held);
        } catch (IllegalStateException failure) {
            System.out.println("HARNESS: FAILED " + failure.getMessage());
            System.out.flush();
            System.exit(4);
        }
    }
}
