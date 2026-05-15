package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PublicationStateStore {

    private static final Path TRANSACTION_ROOT =
            Paths.get("runtime-data", "publication-transactions");
    private static final Path PUBLICATION_ROOT =
            Paths.get("runtime-data", "real-publication-executions");

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public PublicationStateStore(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> rollbackPublicationState(
            String publicationTransactionId,
            String realPublicationExecutionId,
            String rollbackReference
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionUpdated", false);
        result.put("publicationUpdated", false);
        result.put("publicationDbUpdated", false);
        result.put("transactionPath", "");
        result.put("publicationPath", "");

        if (publicationTransactionId != null && !publicationTransactionId.isBlank()) {
            Path transactionPath = TRANSACTION_ROOT.resolve(publicationTransactionId + ".json");
            if (Files.exists(transactionPath)) {
                updateTransaction(transactionPath, rollbackReference);
                result.put("transactionUpdated", true);
                result.put("transactionPath", transactionPath.toString().replace("\\", "/"));
            }
        }

        if (realPublicationExecutionId != null && !realPublicationExecutionId.isBlank()) {
            Path publicationPath = PUBLICATION_ROOT.resolve(realPublicationExecutionId + ".json");
            if (Files.exists(publicationPath)) {
                updatePublication(publicationPath, rollbackReference);
                result.put("publicationUpdated", true);
                result.put("publicationPath", publicationPath.toString().replace("\\", "/"));
            }
            result.put(
                    "publicationDbUpdated",
                    updatePublicationDatabaseState(realPublicationExecutionId, rollbackReference)
            );
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private void updateTransaction(Path path, String rollbackReference) {
        try {
            Map<String, Object> record = objectMapper.readValue(path.toFile(), LinkedHashMap.class);
            record.put("transactionStatus", "ROLLED_BACK_PUBLICATION_STATE");
            record.put("publicationRollbackReference", rollbackReference);
            record.put(
                    "integrityNotes",
                    "Publication transaction state was rolled back while preserving chain traceability."
            );
            record.put("publicationRolledBackAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update publication transaction state.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePublication(Path path, String rollbackReference) {
        try {
            Map<String, Object> record = objectMapper.readValue(path.toFile(), LinkedHashMap.class);
            record.put("publicationStatus", "ROLLED_BACK");
            record.put("publicationOutcome", "PUBLICATION_STATE_RESTORED");
            record.put("publicationRollbackReference", rollbackReference);
            record.put("publicationRolledBackAt", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update publication execution state.", e);
        }
    }

    private boolean updatePublicationDatabaseState(String realPublicationExecutionId, String rollbackReference) {
        int updated = jdbcTemplate.update(
                """
                UPDATE npdev_publication_execution
                SET publication_status = 'ROLLED_BACK',
                    publication_outcome = 'PUBLICATION_STATE_RESTORED',
                    execution_payload = jsonb_set(
                        execution_payload,
                        '{publicationRollbackReference}',
                        to_jsonb(?::text),
                        true
                    ),
                    completed_at = COALESCE(completed_at, NOW()),
                    updated_at = NOW()
                WHERE publication_execution_id = CAST(? AS uuid)
                """,
                rollbackReference,
                realPublicationExecutionId
        );
        return updated > 0;
    }
}
