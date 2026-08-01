package com.finalexec.db;

import com.finalexec.config.SpringTransactionRunner;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): the real-database half of the row-authz
 * check-then-act fix -- {@code com.npdev.kernel.concepts.DefaultConceptGatewayRowAuthzRaceTest}
 * documents the vulnerability with a deterministic single-threaded simulation (no real store can
 * prove the fix, since the guarantee is a genuine database row lock); this proves the fix with two
 * REAL threads against a real H2 database, {@link JdbcBusinessConceptStore}, and a real
 * {@link DataSourceTransactionManager}.
 *
 * <p>Thread A's {@code findByIdForUpdate} is wrapped to pause (still holding H2's real {@code FOR
 * UPDATE} row lock, inside its still-open transaction) right after acquiring it. While paused, Thread
 * B attempts its own {@code save} against the SAME row -- proven to genuinely block (a bounded
 * {@link Future#get} times out) rather than proceed against a stale snapshot, exactly the race B18
 * describes. Releasing Thread A lets its transaction commit, which unblocks Thread B, whose own read
 * now sees Thread A's already-committed state.
 */
class DefaultConceptGatewayRowAuthzRaceTest {

    private DataSource dataSource;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SharedUrlDataSource(url);
        transactionManager = new DataSourceTransactionManager(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tickets (id VARCHAR(50) PRIMARY KEY, owner_id VARCHAR(120) NOT NULL, "
                    + "status VARCHAR(50) NOT NULL, tenant_id VARCHAR(120) NOT NULL)");
        }
    }

    private static CompiledModel ticketModel() {
        CompiledConcept ticket = new CompiledConcept(
                "Ticket", "Ticket", "tickets",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("ownerId", "string", "String", false, true, false),
                        new CompiledField("status", "string", "String", false, true, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(),
                new CompiledConceptAccess(null, "ownerId == $user.id")
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(ticket.getName(), ticket));
    }

    /** Pauses AFTER the delegate's real {@code SELECT ... FOR UPDATE} returns (lock already held by
     * this transaction), signals {@code lockAcquired}, then blocks on {@code releaseLock} before
     * returning control -- so the row lock stays held for a caller-controlled window. */
    private static final class PausingAfterLockStore implements ConceptStore {
        private final ConceptStore delegate;
        private final CountDownLatch lockAcquired;
        private final CountDownLatch releaseLock;

        PausingAfterLockStore(ConceptStore delegate, CountDownLatch lockAcquired, CountDownLatch releaseLock) {
            this.delegate = delegate;
            this.lockAcquired = lockAcquired;
            this.releaseLock = releaseLock;
        }

        @Override
        public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
            Optional<ConceptRecord> snapshot = delegate.findByIdForUpdate(tenantId, conceptName, id);
            lockAcquired.countDown();
            try {
                if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out waiting to be released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return snapshot;
        }

        @Override
        public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
            return delegate.findById(tenantId, conceptName, id);
        }

        @Override
        public List<ConceptRecord> findAll(String tenantId, String conceptName) {
            return delegate.findAll(tenantId, conceptName);
        }

        @Override
        public ConceptRecord save(ConceptRecord record) {
            return delegate.save(record);
        }

        @Override
        public void deleteById(String tenantId, String conceptName, String id) {
            delegate.deleteById(tenantId, conceptName, id);
        }
    }

    @Test
    void aConcurrentReassignmentGenuinelyBlocksUntilTheFirstTransactionCommits() throws Exception {
        JdbcBusinessConceptStore realStore = new JdbcBusinessConceptStore(dataSource, ticketModel());
        realStore.save(new ConceptRecord("Ticket", "T1", "tenant-a", Map.of("id", "T1", "ownerId", "alice", "status", "Open")));

        TransactionRunner transactionRunner = new SpringTransactionRunner(transactionManager);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        PausingAfterLockStore pausingStore = new PausingAfterLockStore(realStore, lockAcquired, releaseLock);
        DefaultConceptGateway gatewayA = DefaultConceptGateway.governedBy(pausingStore, ticketModel(), transactionRunner);
        DefaultConceptGateway gatewayB = DefaultConceptGateway.governedBy(realStore, ticketModel(), transactionRunner);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A: alice updates status -- pauses mid-transaction, real FOR UPDATE lock held.
            Future<ConceptRecord> threadA = pool.submit(() -> gatewayA.save(
                    new ConceptWriteRequest("Ticket", "T1", "tenant-a",
                            Map.of("id", "T1", "ownerId", "alice", "status", "InProgress"), null, false),
                    ExecutionContext.of("tenant-a", "alice")));

            assertTrue(lockAcquired.await(10, TimeUnit.SECONDS), "Thread A must acquire the row lock");

            // Thread B: (still acting as alice, still the CURRENTLY COMMITTED owner) reassigns
            // ownership to bob. Its own findByIdForUpdate must block on the same row -- proven by a
            // bounded get() timing out -- rather than read a stale snapshot and race ahead.
            Future<ConceptRecord> threadB = pool.submit(() -> gatewayB.save(
                    new ConceptWriteRequest("Ticket", "T1", "tenant-a",
                            Map.of("id", "T1", "ownerId", "bob", "status", "Open"), null, false),
                    ExecutionContext.of("tenant-a", "alice")));

            assertThrows(TimeoutException.class, () -> threadB.get(500, TimeUnit.MILLISECONDS),
                    "B18 fix: a concurrent writer's own findByIdForUpdate against the same row must "
                            + "genuinely block while A's transaction still holds the lock, not read a stale snapshot");

            // Release A -- its transaction commits, releasing the lock.
            releaseLock.countDown();
            ConceptRecord savedByA = threadA.get(10, TimeUnit.SECONDS);
            assertEquals("InProgress", savedByA.data().get("status"));

            // B now unblocks, its OWN findByIdForUpdate sees A's already-committed state (owner still
            // alice, status InProgress -- not the pre-A snapshot), and proceeds correctly.
            ConceptRecord savedByB = threadB.get(10, TimeUnit.SECONDS);
            assertEquals("bob", savedByB.data().get("ownerId"));

            ConceptRecord finalState = realStore.findById("tenant-a", "Ticket", "T1").orElseThrow();
            assertEquals("bob", finalState.data().get("ownerId"));
            assertEquals("Open", finalState.data().get("status"),
                    "B's write (the one that ran second, serialized after A committed) is the final state");
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class SharedUrlDataSource implements DataSource {
        private final String url;

        private SharedUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
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
