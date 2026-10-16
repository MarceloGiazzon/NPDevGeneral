package com.finalexec.scheduler;

import com.npdev.kernel.ports.MailMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OPS-11: pure decision logic of the operator alert digest (no Spring, no SMTP). */
class OpsAlertDigestTest {

    private static final List<String> TO = List.of("ops@example.com");

    private static Map<String, Object> card(String id, String flow, String status) {
        return Map.of("executionId", id, "flowName", flow, "status", status,
                "lastErrorCode", "STEP_FAILED", "detailPath", "/api/executions/" + id);
    }

    @Test
    void firstFailedCardProducesAMailNamingFlowAndId() {
        OpsAlertDigest digest = new OpsAlertDigest();
        Optional<MailMessage> mail = digest.evaluate(List.of(card("ex-1", "ApproveOrder", "FAILED")),
                "UP", TO, "Shop", "https://shop.example.com");

        assertTrue(mail.isPresent());
        assertEquals(TO, mail.get().to());
        assertEquals("[Shop] 1 item(s) need attention", mail.get().subject());
        assertTrue(mail.get().body().contains("ApproveOrder"), mail.get().body());
        assertTrue(mail.get().body().contains("ex-1"), mail.get().body());
        assertTrue(mail.get().body().contains("https://shop.example.com/api/executions/ex-1"), mail.get().body());
    }

    @Test
    void sameExecutionIsReportedOnlyOnce() {
        OpsAlertDigest digest = new OpsAlertDigest();
        List<Map<String, Object>> cards = List.of(card("ex-1", "ApproveOrder", "STUCK"));
        assertTrue(digest.evaluate(cards, "UP", TO, "Shop", "").isPresent());
        assertTrue(digest.evaluate(cards, "UP", TO, "Shop", "").isEmpty());

        Optional<MailMessage> next = digest.evaluate(
                List.of(card("ex-1", "ApproveOrder", "STUCK"), card("ex-2", "Ship", "FAILED_PERMANENT")),
                "UP", TO, "Shop", "");
        assertTrue(next.isPresent());
        assertTrue(next.get().body().contains("ex-2") && !next.get().body().contains("ex-1"), next.get().body());
    }

    @Test
    void healthIsReportedOnTransitionsOnly() {
        OpsAlertDigest digest = new OpsAlertDigest();
        Optional<MailMessage> down = digest.evaluate(List.of(), "DOWN", TO, "Shop", "");
        assertTrue(down.isPresent());
        assertTrue(down.get().subject().contains("app DOWN"), down.get().subject());

        assertTrue(digest.evaluate(List.of(), "DOWN", TO, "Shop", "").isEmpty(), "no repeat while still DOWN");

        Optional<MailMessage> up = digest.evaluate(List.of(), "UP", TO, "Shop", "");
        assertTrue(up.isPresent());
        assertTrue(up.get().body().contains("recovered"), up.get().body());

        assertTrue(digest.evaluate(List.of(), "UP", TO, "Shop", "").isEmpty());
    }

    @Test
    void noRecipientsMeansNoMail() {
        OpsAlertDigest digest = new OpsAlertDigest();
        assertTrue(digest.evaluate(List.of(card("ex-1", "F", "FAILED")), "DOWN", List.of(), "Shop", "").isEmpty());
    }
}
