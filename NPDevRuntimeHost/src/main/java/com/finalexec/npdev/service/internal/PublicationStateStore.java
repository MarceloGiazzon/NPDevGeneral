package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public PublicationStateStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> rollbackPublicationState(
            String publicationTransactionId,
            String realPublicationExecutionId,
            String rollbackReference
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionUpdated", false);
        result.put("publicationUpdated", false);
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
}
