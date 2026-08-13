package com.finalexec.npdev.service;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.dbschema.NpdevPromotionStateTable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces the S0-S8 promotion-stage model (per the project's Box/Object/Truth doctrine: "Truth
 * classification never blocks creation. It only blocks false release claims."). Every attempted
 * transition is appended to {@value NpdevPromotionStateTable#NAME} — accepted or rejected — so the
 * app's promotion history is a real audit log, not a single mutable flag a caller could silently
 * overwrite to fake a release claim.
 *
 * <p>Gate rules, deliberately bounded by what is honestly enforceable at runtime: stages can only be
 * advanced one at a time (no skipping); S5+ require non-blank evidence (a test report reference, an
 * evidence summary, an approval rationale); S7 (ReleaseApproved) and S8 (Released) additionally
 * require the caller to hold the platform ADMIN role, so a release claim is always attributable to a
 * real authenticated approver.</p>
 */
@Service
public class PromotionStateService {

    public enum Stage {
        S0_IDEA, S1_DECLARED, S2_GENERATED, S3_CUSTOMIZED, S4_RUNNABLE,
        S5_TESTED, S6_EVIDENCE_BACKED, S7_RELEASE_APPROVED, S8_RELEASED
    }

    private static final Set<Stage> EVIDENCE_REQUIRED = Set.of(
            Stage.S5_TESTED, Stage.S6_EVIDENCE_BACKED, Stage.S7_RELEASE_APPROVED, Stage.S8_RELEASED);
    private static final Set<Stage> ADMIN_ROLE_REQUIRED = Set.of(Stage.S7_RELEASE_APPROVED, Stage.S8_RELEASED);

    private final ObjectProvider<DataSource> dataSourceProvider;

    public PromotionStateService(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    public Map<String, Object> currentState() {
        DataSource dataSource = requireDataSource();
        List<Map<String, Object>> history = history(dataSource);
        Stage current = currentStage(history);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentStage", current.name());
        body.put("history", history);
        return body;
    }

    public Map<String, Object> advance(Stage targetStage, String evidence, ExecutionContext context) {
        if (targetStage == null) {
            throw new IllegalArgumentException("targetStage is required");
        }
        DataSource dataSource = requireDataSource();
        List<Map<String, Object>> history = history(dataSource);
        Stage current = currentStage(history);

        String rejectionReason = rejectionReason(current, targetStage, evidence, context);
        String outcome = rejectionReason == null ? "ACCEPTED" : "REJECTED";
        appendEvent(dataSource, targetStage, context, evidence, outcome, rejectionReason);

        if (rejectionReason != null) {
            throw new PromotionRejectedException(rejectionReason, current, targetStage);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentStage", targetStage.name());
        body.put("previousStage", current.name());
        return body;
    }

    private String rejectionReason(Stage current, Stage target, String evidence, ExecutionContext context) {
        if (target.ordinal() != current.ordinal() + 1) {
            return "stage_skip: must advance from " + current.name() + " to "
                    + Stage.values()[Math.min(current.ordinal() + 1, Stage.values().length - 1)].name()
                    + ", not " + target.name();
        }
        if (EVIDENCE_REQUIRED.contains(target) && (evidence == null || evidence.isBlank())) {
            return "missing_evidence: " + target.name() + " requires non-blank evidence";
        }
        if (ADMIN_ROLE_REQUIRED.contains(target) && (context == null || !context.roles().contains("ADMIN"))) {
            return "missing_role: " + target.name() + " requires the ADMIN role";
        }
        return null;
    }

    private static Stage currentStage(List<Map<String, Object>> history) {
        Stage highest = Stage.S0_IDEA;
        for (Map<String, Object> event : history) {
            if (!"ACCEPTED".equals(event.get("outcome"))) {
                continue;
            }
            Stage stage = Stage.valueOf((String) event.get("stage"));
            if (stage.ordinal() > highest.ordinal()) {
                highest = stage;
            }
        }
        return highest;
    }

    private List<Map<String, Object>> history(DataSource dataSource) {
        String sql = "SELECT event_id, ts_ms, stage, actor_id, roles, evidence, outcome, reason_code "
                + "FROM " + NpdevPromotionStateTable.NAME + " ORDER BY ts_ms ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventId", resultSet.getString("event_id"));
                event.put("timestampMs", resultSet.getLong("ts_ms"));
                event.put("stage", resultSet.getString("stage"));
                event.put("actorId", resultSet.getString("actor_id"));
                event.put("roles", resultSet.getString("roles"));
                event.put("evidence", resultSet.getString("evidence"));
                event.put("outcome", resultSet.getString("outcome"));
                event.put("reasonCode", resultSet.getString("reason_code"));
                out.add(event);
            }
            return out;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading promotion history", exception);
        }
    }

    private void appendEvent(
            DataSource dataSource, Stage stage, ExecutionContext context, String evidence,
            String outcome, String reasonCode
    ) {
        String sql = "INSERT INTO " + NpdevPromotionStateTable.NAME
                + " (event_id, ts_ms, stage, actor_id, roles, evidence, outcome, reason_code) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.setString(3, stage.name());
            statement.setString(4, context == null ? "" : context.actorId());
            statement.setString(5, context == null ? "" : String.join(",", context.roles()));
            statement.setString(6, evidence == null ? "" : evidence);
            statement.setString(7, outcome);
            statement.setString(8, reasonCode == null ? "" : reasonCode);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed recording promotion event", exception);
        }
    }

    private DataSource requireDataSource() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException(
                    "Promotion-stage tracking requires a physical database (H2Local/H2Server/Postgres); "
                            + "this app's engine has no DataSource.");
        }
        return dataSource;
    }

    public static final class PromotionRejectedException extends RuntimeException {
        private final Stage currentStage;
        private final Stage targetStage;

        public PromotionRejectedException(String message, Stage currentStage, Stage targetStage) {
            super(message);
            this.currentStage = currentStage;
            this.targetStage = targetStage;
        }

        public Stage currentStage() {
            return currentStage;
        }

        public Stage targetStage() {
            return targetStage;
        }
    }
}
