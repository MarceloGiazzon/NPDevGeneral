package com.finalexec.config;

import com.finalexec.webhook.QueuedWebhookCapabilityHandler;
import com.finalexec.webhook.WebhookDeliveryWorker;
import com.npdev.adapters.webhook.http.HttpWebhookCapabilityAdapter;
import com.npdev.adapters.webhook.http.WebhookDeliveryRecordStore;
import com.npdev.adapters.webhook.http.WebhookDestinationProfile;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * INT-5: outbound webhooks. Active only for a model that binds some capability to adapter
 * {@code webhook-http}. Operator configuration:
 * <ul>
 *   <li>{@code npdev.webhook.http.destinations} ({@code NPDEV_WEBHOOK_HTTP_DESTINATIONS}):
 *       {@code host|ENV_VAR;host2|ENV_VAR2} -- the allowlisted receiver hosts and, per host, the NAME of the
 *       environment variable holding the shared HMAC secret. Empty = every delivery is denied (fail-closed).</li>
 *   <li>{@code npdev.webhook.http.requestTimeoutMs} (default 15000) -- per-attempt deadline.</li>
 *   <li>{@code npdev.webhook.http.worker.intervalMs} / {@code initialDelayMs} -- queue drain cadence.</li>
 * </ul>
 */
@Configuration
public class NpdevWebhookConfig {

    private static boolean bindsWebhookHttp(ModelHolder modelHolder) {
        return modelHolder.get().getBindings().stream()
                .anyMatch(binding -> QueuedWebhookCapabilityHandler.ADAPTER_ID.equalsIgnoreCase(binding.getAdapter()));
    }

    /** Returns null (the "no bean" idiom) unless the model binds webhook-http. Inject only via ObjectProvider. */
    @Bean
    public HttpWebhookCapabilityAdapter webhookHttpAdapter(
            ModelHolder modelHolder,
            @Value("${npdev.webhook.http.destinations:}") String destinations,
            @Value("${npdev.webhook.http.requestTimeoutMs:15000}") long requestTimeoutMs
    ) {
        if (!bindsWebhookHttp(modelHolder)) {
            return null;
        }
        // maxRetries 0: the worker owns retry/backoff. deliveryStore null: the worker owns persistence.
        return new HttpWebhookCapabilityAdapter(
                parseDestinations(destinations),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                System::getenv,
                Duration.ofMillis(requestTimeoutMs),
                0,
                Duration.ZERO,
                null,
                null);
    }

    @Bean
    public WebhookDeliveryRecordStore webhookDeliveryRecordStore() {
        return new WebhookDeliveryRecordStore(SqlDialects.active());
    }

    @Bean
    public QueuedWebhookCapabilityHandler queuedWebhookCapabilityHandler(
            ObjectProvider<HttpWebhookCapabilityAdapter> adapter,
            ObjectProvider<DataSource> dataSource,
            WebhookDeliveryRecordStore store
    ) {
        return new QueuedWebhookCapabilityHandler(adapter::getIfAvailable, dataSource::getIfAvailable, store);
    }

    @Bean
    public WebhookDeliveryWorker webhookDeliveryWorker(
            ObjectProvider<HttpWebhookCapabilityAdapter> adapter,
            ObjectProvider<DataSource> dataSource,
            WebhookDeliveryRecordStore store
    ) {
        return new WebhookDeliveryWorker(adapter::getIfAvailable, dataSource::getIfAvailable, store);
    }

    static List<WebhookDestinationProfile> parseDestinations(String rawConfig) {
        List<WebhookDestinationProfile> destinations = new ArrayList<>();
        if (rawConfig == null || rawConfig.isBlank()) {
            return destinations;
        }
        for (String entry : rawConfig.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] fields = entry.split("\\|", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalStateException("Malformed npdev.webhook.http.destinations entry (expected "
                        + "'host|HMAC_SECRET_ENV_VAR'): '" + entry + "'");
            }
            destinations.add(WebhookDestinationProfile.of(fields[0].trim(), fields[1].trim()));
        }
        return destinations;
    }
}
