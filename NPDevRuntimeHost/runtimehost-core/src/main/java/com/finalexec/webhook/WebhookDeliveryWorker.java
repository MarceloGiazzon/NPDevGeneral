package com.finalexec.webhook;

import com.npdev.adapters.webhook.http.HttpWebhookCapabilityAdapter;
import com.npdev.adapters.webhook.http.WebhookBackoff;
import com.npdev.adapters.webhook.http.WebhookDeliveryRecordStore;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * INT-5: drains the durable webhook queue {@link QueuedWebhookCapabilityHandler} fills. One pass claims
 * each due row (optimistic UPDATE, so several app instances never double-send), makes ONE signed attempt
 * through {@link HttpWebhookCapabilityAdapter#deliverOnce}, and then marks it delivered, reschedules it with
 * {@link WebhookBackoff}, or dead-letters it (FAILED) once the attempts are used up or the failure is
 * permanent. Logs carry ids, hosts and statuses -- never the payload (it may hold personal data).
 */
public class WebhookDeliveryWorker {

    private static final Logger LOG = Logger.getLogger(WebhookDeliveryWorker.class.getName());
    static final int BATCH_SIZE = 25;
    static final Duration STALE_SENDING_AFTER = Duration.ofMinutes(10);

    private final Supplier<HttpWebhookCapabilityAdapter> adapter;
    private final Supplier<DataSource> dataSource;
    private final WebhookDeliveryRecordStore store;

    public WebhookDeliveryWorker(
            Supplier<HttpWebhookCapabilityAdapter> adapter,
            Supplier<DataSource> dataSource,
            WebhookDeliveryRecordStore store
    ) {
        this.adapter = adapter;
        this.dataSource = dataSource;
        this.store = store;
    }

    @Scheduled(
            fixedDelayString = "${npdev.webhook.http.worker.intervalMs:5000}",
            initialDelayString = "${npdev.webhook.http.worker.initialDelayMs:10000}"
    )
    public void drain() {
        try {
            drainOnce(Instant.now());
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "webhook delivery pass failed: " + failure.getClass().getSimpleName());
        }
    }

    /** One drain pass; returns how many deliveries were attempted. Also the entry point tests call. */
    public int drainOnce(Instant now) {
        HttpWebhookCapabilityAdapter http = adapter.get();
        DataSource source = dataSource.get();
        if (http == null || source == null) {
            return 0;
        }
        int attempted = 0;
        try (Connection connection = source.getConnection()) {
            store.ensureSchema(connection);
            store.releaseStale(connection, now.minus(STALE_SENDING_AFTER), now);
            List<WebhookDeliveryRecordStore.DueDelivery> due = store.findDue(connection, now, BATCH_SIZE);
            for (WebhookDeliveryRecordStore.DueDelivery delivery : due) {
                if (!store.claimForSend(connection, delivery.id(), now)) {
                    continue; // another worker won it
                }
                attempted++;
                int attemptNumber = delivery.attemptCount() + 1;
                HttpWebhookCapabilityAdapter.DeliveryAttempt attempt =
                        http.deliverOnce(delivery.id(), delivery.url(), delivery.payloadJson(), delivery.eventName());
                Instant finished = Instant.now();
                if (attempt.delivered()) {
                    store.finishDelivered(connection, delivery.id(), attempt.httpStatus(), finished);
                    LOG.info(() -> "webhook " + delivery.id() + " delivered (HTTP " + attempt.httpStatus()
                            + ", attempt " + attemptNumber + ")");
                } else if (!attempt.retryable() || !WebhookBackoff.hasAttemptsLeft(attemptNumber)) {
                    store.finishFailed(connection, delivery.id(), attempt.httpStatus(), attempt.error(), finished);
                    LOG.warning(() -> "webhook " + delivery.id() + " dead-lettered after attempt " + attemptNumber
                            + " (HTTP " + attempt.httpStatus() + ")");
                } else {
                    Instant next = finished.plus(WebhookBackoff.delayAfter(attemptNumber));
                    store.reschedule(connection, delivery.id(), attempt.error(), next, finished);
                    LOG.info(() -> "webhook " + delivery.id() + " attempt " + attemptNumber + " failed (HTTP "
                            + attempt.httpStatus() + "); next attempt at " + next);
                }
            }
        } catch (SQLException failure) {
            LOG.log(Level.WARNING, "webhook delivery store unavailable: " + failure.getClass().getSimpleName());
        }
        return attempted;
    }
}
