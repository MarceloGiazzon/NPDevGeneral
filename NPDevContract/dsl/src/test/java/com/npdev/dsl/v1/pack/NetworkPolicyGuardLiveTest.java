package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-5 step 2's own literal proof request: "point NetworkPolicy at a deliberately unreachable host
 * and confirm generate never attempts it while add/update does." This binds a real TCP port, closes
 * it immediately (nothing listens -- any real connection attempt gets a fast "connection refused"
 * rather than hanging), and shows the two policies behave differently in a way only explainable by
 * one of them actually trying the network and the other refusing before it ever would:
 *
 * <ul>
 *   <li>{@link NetworkPolicy#DENIED} throws {@link NetworkPolicyViolationException} -- fast,
 *       deterministic, no process spawned, regardless of coordinate scheme.</li>
 *   <li>{@link NetworkPolicy#ALLOWED} against the git scheme spawns a real {@code git} process
 *       that actually dials the closed port and fails with a connection error (a DIFFERENT
 *       exception type, {@link IOException} but never {@link NetworkPolicyViolationException}) --
 *       proof an attempt genuinely happened.</li>
 * </ul>
 */
class NetworkPolicyGuardLiveTest {

    @TempDir
    Path work;

    @Test
    void deniedRefusesAGitCoordinateAtAnUnreachableHostWithoutAttemptingIt() throws Exception {
        try (ServerSocket closedSocket = new ServerSocket(0)) {
            int port = closedSocket.getLocalPort();
            closedSocket.close(); // now definitely refusing connections

            PackCoordinate coordinate = PackCoordinate.parse(
                    "git+http://127.0.0.1:" + port + "/unreachable-repo.git@v1.0.0");
            PackCache cache = new PackCache(work.resolve("cache"));

            NetworkPolicyViolationException failure = assertThrows(NetworkPolicyViolationException.class,
                    () -> RemotePackFetcher.fetch(coordinate, NetworkPolicy.DENIED, cache));
            assertTrue(failure.getMessage().contains("127.0.0.1:" + port), failure.getMessage());
        }
    }

    @Test
    void allowedActuallyAttemptsTheConnectionAndFailsWithAConnectionErrorNotAGuardRefusal() throws Exception {
        try (ServerSocket closedSocket = new ServerSocket(0)) {
            int port = closedSocket.getLocalPort();
            closedSocket.close();

            PackCoordinate coordinate = PackCoordinate.parse(
                    "git+http://127.0.0.1:" + port + "/unreachable-repo.git@v1.0.0");
            PackCache cache = new PackCache(work.resolve("cache"));

            IOException failure = assertThrows(IOException.class,
                    () -> RemotePackFetcher.fetch(coordinate, NetworkPolicy.ALLOWED, cache));
            assertFalse(failure instanceof NetworkPolicyViolationException,
                    "ALLOWED must reach the real git process, not the network-policy guard: " + failure);
            assertTrue(failure.getMessage().contains("git clone failed"), failure.getMessage());
        }
    }

    @Test
    void deniedRefusesAnOciCoordinateTheSameWayBeforeDispatchingToTheUnimplementedFetcher() throws Exception {
        PackCoordinate coordinate = PackCoordinate.parse("oci://127.0.0.1:1/unreachable/repo:1.0.0");
        PackCache cache = new PackCache(work.resolve("cache"));

        // The guard applies uniformly regardless of whether the scheme's fetch is even implemented
        // -- DENIED must throw the guard's own exception type, never reach (and therefore never be
        // masked by) OCI's "not implemented in this slice" UnsupportedOperationException.
        assertThrows(NetworkPolicyViolationException.class,
                () -> RemotePackFetcher.fetch(coordinate, NetworkPolicy.DENIED, cache));
    }

    @Test
    void allowedPassesTheGuardForOciAndReachesTheDocumentedNotImplementedStub() throws Exception {
        PackCoordinate coordinate = PackCoordinate.parse("oci://127.0.0.1:1/unreachable/repo:1.0.0");
        PackCache cache = new PackCache(work.resolve("cache"));

        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> RemotePackFetcher.fetch(coordinate, NetworkPolicy.ALLOWED, cache));
        assertTrue(failure.getMessage().contains("not implemented"), failure.getMessage());
    }
}
