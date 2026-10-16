package com.finalexec.npdev.service;

import com.finalexec.scheduler.OpsAlertDigest;
import com.npdev.adapters.mail.smtp.SmtpMailCapabilityAdapter;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.MailMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OPS-11: e-mails a short digest when a flow execution FAILS or becomes STUCK and when the app's
 * health leaves or returns to UP. Off while {@code npdev.alerts.mail-to} (env
 * {@code NPDEV_ALERTS_MAIL_TO}) or the SMTP host is blank. Reuses the {@code npdev.mail.smtp.*}
 * settings; home tenant only. What to send is decided by the pure {@link OpsAlertDigest}.
 *
 * <p>Per-app template class: it depends on {@link ExecutionMonitorService}, which needs the
 * generated KernelFacade, so it compiles only inside a generated app.
 */
@Component
public class OpsAlertScheduler {

    private static final Logger LOG = Logger.getLogger(OpsAlertScheduler.class.getName());

    private final ExecutionMonitorService executionMonitor;
    private final HealthEndpoint healthEndpoint;
    private final List<String> recipients;
    private final String baseUrl;
    private final String tenantId;
    private final String appName;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final String smtpFrom;
    private final boolean smtpStartTls;
    private final OpsAlertDigest digest = new OpsAlertDigest();
    private boolean loggedOff;

    public OpsAlertScheduler(
            ExecutionMonitorService executionMonitor,
            HealthEndpoint healthEndpoint,
            @Value("${npdev.alerts.mail-to:}") String mailTo,
            @Value("${npdev.alerts.base-url:}") String baseUrl,
            @Value("${npdev.tenant.default-id:dev}") String tenantId,
            @Value("${spring.application.name:NPDev app}") String appName,
            @Value("${npdev.mail.smtp.host:}") String smtpHost,
            @Value("${npdev.mail.smtp.port:25}") int smtpPort,
            @Value("${npdev.mail.smtp.username:}") String smtpUsername,
            @Value("${npdev.mail.smtp.password:}") String smtpPassword,
            @Value("${npdev.mail.smtp.from:no-reply@example.com}") String smtpFrom,
            @Value("${npdev.mail.smtp.starttls:true}") boolean smtpStartTls) {
        this.executionMonitor = executionMonitor;
        this.healthEndpoint = healthEndpoint;
        this.recipients = Arrays.stream(mailTo == null ? new String[0] : mailTo.split("[,;\\s]+"))
                .filter(address -> !address.isBlank()).toList();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.tenantId = tenantId;
        this.appName = appName;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
        this.smtpFrom = smtpFrom;
        this.smtpStartTls = smtpStartTls;
    }

    @Scheduled(fixedDelayString = "${npdev.alerts.interval-ms:300000}",
            initialDelayString = "${npdev.alerts.initial-delay-ms:60000}")
    public void check() {
        if (recipients.isEmpty() || smtpHost == null || smtpHost.isBlank()) {
            if (!loggedOff) {
                loggedOff = true;
                LOG.info("OPS-11: operator alerts are off (set NPDEV_ALERTS_MAIL_TO and the npdev.mail.smtp.* settings to enable)");
            }
            return;
        }
        try {
            ExecutionContext context = ExecutionContext.system(tenantId);
            List<Map<String, Object>> cards = new ArrayList<>(items(executionMonitor.stuck(context)));
            for (Map<String, Object> card : items(executionMonitor.history(context))) {
                if ("FAILED".equals(card.get("outcome"))) {
                    cards.add(card);
                }
            }
            String health = healthEndpoint.health().getStatus().getCode();
            Optional<MailMessage> mail = digest.evaluate(cards, health, recipients, appName, baseUrl);
            if (mail.isPresent()) {
                new SmtpMailCapabilityAdapter(smtpHost, smtpPort, smtpUsername, smtpPassword, smtpFrom, smtpStartTls)
                        .send(mail.get());
                LOG.info(() -> "OPS-11: alert mail sent to " + recipients + ": " + mail.get().subject());
            }
        } catch (Exception failure) {
            // An alert failure must never break the app.
            LOG.log(Level.WARNING, "OPS-11: operator alert check failed", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> response) {
        Object items = response.get("items");
        return items instanceof List<?> ? (List<Map<String, Object>>) items : List.of();
    }
}
