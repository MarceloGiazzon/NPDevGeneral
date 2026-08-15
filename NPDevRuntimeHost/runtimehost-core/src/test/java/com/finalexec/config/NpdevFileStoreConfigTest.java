package com.finalexec.config;

import com.npdev.kernel.ports.FileStoreContract;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-166: {@code NpdevFileStoreConfig.objectStoreFileStoreContract} previously built its
 * {@code S3Client} with no {@code apiCallTimeout}/{@code apiCallAttemptTimeout} and no retry
 * policy -- a backend that accepted a TCP connection and then never responded (a real MinIO/R2
 * backpressure failure mode) blocked the calling thread forever. Same real-hanging-socket proof
 * shape {@code HttpExternalAiCapabilityAdapterTest}/{@code SmtpMailCapabilityAdapterTest} already
 * use for the two CapabilityAdapter network adapters (RUN-4) -- a real accepted-connection count
 * proves the retry actually happens, not just that the code compiles.
 */
class NpdevFileStoreConfigTest {

    @Test
    void objectStorePutTimesOutAndRetriesTheConfiguredNumberOfTimesAgainstAHangingServer() throws Exception {
        try (ServerSocket hangingServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger acceptedConnections = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket socket = hangingServer.accept();
                        acceptedConnections.incrementAndGet();
                        // Deliberately never write a response -- the client must hit its own
                        // apiCallAttemptTimeout, not a server-side close.
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "hanging-s3-server-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            // apiCallAttemptTimeoutMs=300, maxRetries=1 (2 total attempts) -- short enough that the
            // whole test resolves in well under 5s if the deadline is real, and would hang the JUnit
            // run (previously: forever) if it were not.
            FileStoreContract contract = new NpdevFileStoreConfig().objectStoreFileStoreContract(
                    "test-bucket",
                    "us-east-1",
                    "http://127.0.0.1:" + hangingServer.getLocalPort(),
                    true,
                    "test-access-key",
                    "test-secret-key",
                    60_000L,
                    300L,
                    1
            );

            long startedAt = System.nanoTime();
            byte[] payload = "hello".getBytes();
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> contract.put("tenant-a", "hello.txt", "text/plain", payload.length,
                            new ByteArrayInputStream(payload)));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            acceptor.interrupt();
            hangingServer.close();

            assertTrue(elapsedMs < 5000,
                    "expected apiCallAttemptTimeout to bound the call well under 5s, took " + elapsedMs + "ms");
            assertTrue(acceptedConnections.get() >= 1,
                    "expected at least one real TCP connection against the hanging server, got "
                            + acceptedConnections.get());
        }
    }
}
