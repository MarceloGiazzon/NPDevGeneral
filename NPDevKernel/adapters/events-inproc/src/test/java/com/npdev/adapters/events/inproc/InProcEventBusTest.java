package com.npdev.adapters.events.inproc;

import com.npdev.kernel.events.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcEventBusTest {

    @Test
    void dispatchesEnvelopeToSubscribers() {
        InProcEventBus bus = new InProcEventBus();
        AtomicReference<EventEnvelope> received = new AtomicReference<>();

        bus.subscribe("UserCreated", received::set);
        EventEnvelope envelope = new EventEnvelope(
                "evt-1",
                "UserCreated",
                1704067200000L,
                Map.of("id", "u1"),
                "corr-1",
                "cause-0",
                "CreateUser",
                0
        );

        bus.publish(envelope);
        assertEquals(envelope, received.get());
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        InProcEventBus bus = new InProcEventBus();
        AtomicReference<EventEnvelope> received = new AtomicReference<>();

        AutoCloseable token = bus.subscribe("UserCreated", received::set);
        token.close();

        bus.publish(EventEnvelope.of("UserCreated", Map.of("id", "u2")));
        assertNull(received.get());
    }

    @Test
    void publishPropagatesHandlerFailureAndStillStoresFact() {
        InProcEventBus bus = new InProcEventBus();
        bus.subscribe("UserCreated", event -> {
            throw new IllegalStateException("subscriber failed");
        });

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> bus.publish(EventEnvelope.of("UserCreated", Map.of("id", "u3"))));
        assertEquals("subscriber failed", ex.getMessage());
    }

    @Test
    void supportsEventStoreQueriesByCorrelationAndEventName() {
        InProcEventStore store = new InProcEventStore();
        EventEnvelope one = EventEnvelope.of(
                "InvoiceIssued",
                Map.of("id", "inv-1"),
                "corr-a",
                "exec-1",
                "IssueInvoice",
                0,
                Map.of()
        );
        EventEnvelope two = EventEnvelope.of(
                "InvoiceApproved",
                Map.of("id", "inv-1"),
                "corr-a",
                "exec-1",
                "IssueInvoice",
                1,
                Map.of()
        );
        EventEnvelope three = EventEnvelope.of(
                "InvoiceIssued",
                Map.of("id", "inv-2"),
                "corr-b",
                "exec-2",
                "IssueInvoice",
                0,
                Map.of()
        );

        store.append(one);
        store.append(two);
        store.append(three);

        assertEquals(2, store.readByCorrelation("corr-a").size());
        assertEquals(2, store.readByEventName("InvoiceIssued").size());
        assertEquals(1, store.read("InvoiceApproved", "corr-a").size());
        assertEquals(0, store.read("InvoiceApproved", "corr-missing").size());
        assertEquals(one.eventId(), store.findFirst("InvoiceIssued", "corr-a").orElseThrow().eventId());
    }

    @Test
    void findFirstUsesDeterministicOrderingByTimestampThenEventId() {
        InProcEventStore store = new InProcEventStore();
        EventEnvelope later = new EventEnvelope(
                "evt-2",
                "InvoiceIssued",
                2000L,
                Map.of("id", "inv-100"),
                "corr-deterministic",
                "exec-1",
                "IssueInvoice",
                1
        );
        EventEnvelope earlier = new EventEnvelope(
                "evt-1",
                "InvoiceIssued",
                1000L,
                Map.of("id", "inv-100"),
                "corr-deterministic",
                "exec-1",
                "IssueInvoice",
                0
        );
        EventEnvelope sameTimeLowerId = new EventEnvelope(
                "evt-0",
                "InvoiceIssued",
                1000L,
                Map.of("id", "inv-100"),
                "corr-deterministic",
                "exec-1",
                "IssueInvoice",
                2
        );

        store.append(later);
        store.append(earlier);
        store.append(sameTimeLowerId);

        EventEnvelope first = store.findFirst("InvoiceIssued", "corr-deterministic").orElseThrow();
        assertEquals("evt-0", first.eventId());
    }

    @Test
    void supportsTenantScopedReadQueries() {
        InProcEventStore store = new InProcEventStore();
        EventEnvelope tenantAFirst = new EventEnvelope(
                "evt-a-1",
                "InvoiceIssued",
                1000L,
                Map.of("id", "inv-a1"),
                "corr-shared",
                "exec-a",
                "IssueInvoice",
                0,
                "tenant-a",
                "actor-a"
        );
        EventEnvelope tenantASecond = new EventEnvelope(
                "evt-a-2",
                "InvoiceIssued",
                2000L,
                Map.of("id", "inv-a2"),
                "corr-shared",
                "exec-a",
                "IssueInvoice",
                1,
                "tenant-a",
                "actor-a"
        );
        EventEnvelope tenantBOther = new EventEnvelope(
                "evt-b-1",
                "InvoiceIssued",
                1500L,
                Map.of("id", "inv-b1"),
                "corr-shared",
                "exec-b",
                "IssueInvoice",
                2,
                "tenant-b",
                "actor-b"
        );
        store.append(tenantAFirst);
        store.append(tenantASecond);
        store.append(tenantBOther);

        assertEquals(List.of(tenantAFirst.eventId(), tenantASecond.eventId()),
                store.findByCorrelationId("tenant-a", "corr-shared", 50, 0)
                        .stream().map(EventEnvelope::eventId).toList());
        assertEquals(List.of(tenantASecond.eventId(), tenantAFirst.eventId()),
                store.findByEventName("tenant-a", "InvoiceIssued", 50, 0)
                        .stream().map(EventEnvelope::eventId).toList());
        assertEquals(tenantAFirst.eventId(),
                store.findByEventId("tenant-a", tenantAFirst.eventId()).orElseThrow().eventId());
        assertTrue(store.findByEventId("tenant-a", tenantBOther.eventId()).isEmpty());
    }

    @Test
    void findFirstSupportsTenantScopedLookupForKernelAwaitSafety() {
        InProcEventStore store = new InProcEventStore();
        store.append(new EventEnvelope(
                "evt-b",
                "InvoiceApproved",
                1000L,
                Map.of("status", "TENANT_B"),
                "corr-collision",
                "cause-b",
                "external",
                0,
                "tenant-b",
                "actor-b"
        ));
        store.append(new EventEnvelope(
                "evt-a",
                "InvoiceApproved",
                2000L,
                Map.of("status", "TENANT_A"),
                "corr-collision",
                "cause-a",
                "external",
                0,
                "tenant-a",
                "actor-a"
        ));

        EventEnvelope firstForTenantA = store.findFirst("InvoiceApproved", "corr-collision", "tenant-a").orElseThrow();
        assertEquals("evt-a", firstForTenantA.eventId());
        assertEquals(List.of("evt-a"),
                store.readByCorrelation("corr-collision", "tenant-a").stream().map(EventEnvelope::eventId).toList());
        assertEquals(List.of("evt-a"),
                store.readByEventName("InvoiceApproved", "tenant-a").stream().map(EventEnvelope::eventId).toList());
    }
}

