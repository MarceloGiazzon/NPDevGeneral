package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SupportDiagnosticsService {

    private static final Path DIRECT_EXECUTION_ROOT = Path.of("runtime-data", "direct-executions");
    private static final Path CROSS_TENANT_GOVERNANCE_ROOT = Path.of("runtime-data", "cross-tenant-governance");
    private static final Path GOVERNANCE_WORKSPACE_ROOT = Path.of("runtime-data", "governance-workspace-decisions");
    private static final Path PUBLICATION_FAILURE_RECOVERY_ROOT = Path.of("runtime-data", "publication-failure-recovery");
    private static final Path ROLLBACK_EXECUTION_ROOT = Path.of("runtime-data", "rollback-executions");

    private final PublicationChainReferenceResolver referenceResolver;
    private final DataSource dataSource;
    private final CompiledModel compiledModel;

    public SupportDiagnosticsService(
            PublicationChainReferenceResolver referenceResolver,
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<CompiledModel> compiledModelProvider
    ) {
        this.referenceResolver = referenceResolver;
        this.dataSource = dataSourceProvider == null ? null : dataSourceProvider.getIfAvailable();
        this.compiledModel = compiledModelProvider == null ? null : compiledModelProvider.getIfAvailable();
    }

    public Map<String, Object> diagnostics(ExecutionContext requesterContext) {
        List<Map<String, Object>> issueItems = issues(requesterContext).containsKey("items")
                ? castItems(issues(requesterContext).get("items"))
                : List.of();
        List<Map<String, Object>> blockedItems = blockedStates(requesterContext).containsKey("items")
                ? castItems(blockedStates(requesterContext).get("items"))
                : List.of();
        List<Map<String, Object>> traceItems = traces(requesterContext).containsKey("items")
                ? castItems(traces(requesterContext).get("items"))
                : List.of();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Observability and Support Diagnostics v1");
        response.put("requesterTenantId", requesterContext == null ? "" : requesterContext.tenantId());
        response.put("issueCount", issueItems.size());
        response.put("blockedStateCount", blockedItems.size());
        response.put("traceCount", traceItems.size());
        response.put("highSeverityIssueCount", countSeverity(issueItems, "HIGH"));
        response.put("likelyNextInspectionPoints", List.of(
                "/execution-monitor",
                "/explainability-graph",
                "/governance-workspace",
                "/runtime-topology-explorer",
                "/api/admin/publication-rollback/history",
                "/api/admin/publication-failure-recovery/history"
        ));
        response.put("diagnosticPosture", issueItems.isEmpty() && blockedItems.isEmpty()
                ? "QUIET_RUNTIME_OR_LIGHT_EVIDENCE"
                : "SUPPORT_DIAGNOSTICS_LINKED_TO_REAL_RECORDS");
        return response;
    }

    public Map<String, Object> issues(ExecutionContext requesterContext) {
        List<Map<String, Object>> items = new ArrayList<>();

        for (Map<String, Object> record : referenceResolver.readRecords(DIRECT_EXECUTION_ROOT)) {
            String status = executionStatus(record);
            if ("REJECTED".equals(status) || isFailure(status)) {
                items.add(issueFromDirectExecution(record, status));
            }
        }

        for (Map<String, Object> record : referenceResolver.readRecords(CROSS_TENANT_GOVERNANCE_ROOT)) {
            String status = referenceResolver.extractFirstString(record, "governanceDecision", "governanceOutcome", "governanceStatus");
            if ("CROSS_TENANT_ALLOWED_WITH_AUDIT".equals(status) || "CROSS_TENANT_REJECTED".equals(status) || status.contains("REJECT")) {
                items.add(issueFromGovernance(record, status));
            }
        }

        items.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.get("severity"))).reversed());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Observability and Support Diagnostics v1");
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> traces(ExecutionContext requesterContext) {
        List<Map<String, Object>> items = new ArrayList<>();

        for (Map<String, Object> record : referenceResolver.readRecords(DIRECT_EXECUTION_ROOT)) {
            String reference = referenceResolver.extractFirstString(record, "directExecutionReference", "directExecutionId");
            String executionId = nestedResultField(record, "executionId");
            String flowName = referenceResolver.extractFirstString(record, "flowName");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("traceReference", reference);
            item.put("flowName", flowName);
            item.put("executionId", executionId);
            item.put("traceLinks", List.of(
                    link("Execution Monitor", "/execution-monitor"),
                    link("Execution Detail", executionId.isBlank() ? "/execution-monitor" : "/api/executions/" + executionId),
                    link("Explainability", "/explainability-graph"),
                    link("Governance Workspace", "/governance-workspace"),
                    link("Runtime Topology", "/runtime-topology-explorer"),
                    link("Rollback History", "/api/admin/publication-rollback/history")
            ));
            item.put("traceSummary", "support trace");
            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Observability and Support Diagnostics v1");
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> blockedStates(ExecutionContext requesterContext) {
        List<Map<String, Object>> items = new ArrayList<>();

        for (Map<String, Object> record : referenceResolver.readRecords(DIRECT_EXECUTION_ROOT)) {
            String status = executionStatus(record);
            if ("WAITING_EVENT".equals(status) || "REJECTED".equals(status) || isFailure(status)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("blockedStateReference", referenceResolver.extractFirstString(record, "directExecutionReference", "directExecutionId"));
                item.put("flowName", referenceResolver.extractFirstString(record, "flowName"));
                item.put("status", status);
                item.put("tenantScopeStatus", referenceResolver.extractFirstString(record, "tenantScopeStatus"));
                item.put("blockReason", blockReason(record, status));
                item.put("nextInspection", List.of(
                        "/execution-monitor",
                        "/explainability-graph",
                        "/governance-workspace",
                        "/runtime-topology-explorer"
                ));
                items.add(item);
            }
        }

        for (Map<String, Object> record : referenceResolver.readRecords(PUBLICATION_FAILURE_RECOVERY_ROOT)) {
            String status = referenceResolver.extractFirstString(record, "failureRecoveryStatus", "recoveryStatus");
            if (!status.isBlank()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("blockedStateReference", referenceResolver.extractFirstString(record, "failureRecoveryReference", "recoveryReference"));
                item.put("flowName", referenceResolver.extractFirstString(record, "flowName", "publicationReference"));
                item.put("status", status);
                item.put("tenantScopeStatus", referenceResolver.extractFirstString(record, "tenantId"));
                item.put("blockReason", "Recovery record requires operator review.");
                item.put("nextInspection", List.of(
                        "/api/admin/publication-failure-recovery/history",
                        "/execution-monitor",
                        "/runtime-topology-explorer"
                ));
                items.add(item);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Observability and Support Diagnostics v1");
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> businessPersistenceEvidence(ExecutionContext requesterContext) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Business Persistence Evidence v1");
        response.put("requesterTenantId", requesterContext == null ? "" : requesterContext.tenantId());
        if (dataSource == null) {
            response.put("status", "datasource_unavailable");
            return response;
        }

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            response.put("status", "ok");
            response.put("jdbcUrl", metaData == null ? "" : firstNonBlank(metaData.getURL()));
            response.put("flywayRuntimeMigrationsApplied", flywayScripts(connection, "V%__%"));
            response.put("businessMigrationsApplied", flywayScripts(connection, "R__npdev_business_%"));

            List<Map<String, Object>> tables = new ArrayList<>();
            for (CompiledConcept concept : compiledModel == null ? List.<CompiledConcept>of() : compiledModel.getConcepts()) {
                String tableName = concept.getTableName() == null || concept.getTableName().isBlank()
                        ? concept.getName().toLowerCase() + "s"
                        : concept.getTableName().trim().toLowerCase();
                boolean exists = tableExists(metaData, tableName);
                Map<String, Object> table = new LinkedHashMap<>();
                table.put("concept", concept.getName());
                table.put("tableName", tableName);
                table.put("exists", exists);
                table.put("rowCount", exists ? rowCount(connection, tableName) : null);
                tables.add(table);
            }
            response.put("businessTables", tables);
            return response;
        } catch (Exception exception) {
            response.put("status", "failed");
            response.put("error", exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage());
            return response;
        }
    }

    private static List<Map<String, Object>> flywayScripts(Connection connection, String scriptLikePattern) {
        if (connection == null) {
            return List.of();
        }
        String sql = "SELECT version, description, script, success FROM flyway_schema_history "
                + "WHERE script LIKE ? AND success = TRUE ORDER BY installed_rank";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, scriptLikePattern);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> scripts = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> script = new LinkedHashMap<>();
                    script.put("version", rows.getString("version"));
                    script.put("description", rows.getString("description"));
                    script.put("script", rows.getString("script"));
                    script.put("success", rows.getBoolean("success"));
                    scripts.add(script);
                }
                return scripts;
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static boolean tableExists(DatabaseMetaData metaData, String tableName) {
        if (metaData == null || tableName == null || tableName.isBlank()) {
            return false;
        }
        for (String candidate : List.of(tableName, tableName.toLowerCase(), tableName.toUpperCase())) {
            try (ResultSet rows = metaData.getTables(null, null, candidate, new String[]{"TABLE"})) {
                if (rows.next()) {
                    return true;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static long rowCount(Connection connection, String tableName) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private Map<String, Object> issueFromDirectExecution(Map<String, Object> record, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("issueReference", referenceResolver.extractFirstString(record, "directExecutionReference", "directExecutionId"));
        item.put("category", "DIRECT_EXECUTION");
        item.put("severity", "REJECTED".equals(status) ? "MEDIUM" : "HIGH");
        item.put("flowName", referenceResolver.extractFirstString(record, "flowName"));
        item.put("status", status);
        item.put("summary", firstNonBlank(
                nestedResultField(record, "error"),
                nestedResultField(record, "errorMessage"),
                referenceResolver.extractFirstString(record, "rejectionReason")
        ));
        item.put("nextInspection", List.of(
                "/execution-monitor",
                "/explainability-graph",
                "/runtime-topology-explorer"
        ));
        return item;
    }

    private Map<String, Object> issueFromGovernance(Map<String, Object> record, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("issueReference", referenceResolver.extractFirstString(record, "governanceReference", "crossTenantGovernanceReference"));
        item.put("category", "GOVERNANCE");
        item.put("severity", "HIGH");
        item.put("flowName", referenceResolver.extractFirstString(record, "scope", "targetReference"));
        item.put("status", status);
        item.put("summary", "Governance review or rejection requires support/operator interpretation.");
        item.put("nextInspection", List.of(
                "/governance-workspace",
                "/execution-monitor",
                "/runtime-topology-explorer"
        ));
        return item;
    }

    private int countSeverity(List<Map<String, Object>> items, String severity) {
        int count = 0;
        for (Map<String, Object> item : items) {
            if (severity.equals(item.get("severity"))) {
                count++;
            }
        }
        return count;
    }

    private String executionStatus(Map<String, Object> record) {
        String status = referenceResolver.extractFirstString(record, "executionResultStatus");
        if (!status.isBlank()) {
            return status;
        }
        return nestedResultField(record, "status");
    }

    @SuppressWarnings("unchecked")
    private String nestedResultField(Map<String, Object> record, String key) {
        Object raw = record.get("result");
        if (raw instanceof Map<?, ?> nested) {
            Object value = ((Map<String, Object>) nested).get(key);
            return value == null ? "" : String.valueOf(value).trim();
        }
        return "";
    }

    private boolean isFailure(String status) {
        return !status.isBlank()
                && !"OK".equals(status)
                && !"WAITING_EVENT".equals(status)
                && !"REJECTED".equals(status);
    }

    private String blockReason(Map<String, Object> record, String status) {
        if ("WAITING_EVENT".equals(status)) {
            return "Execution is waiting for an event or follow-up signal.";
        }
        if ("REJECTED".equals(status)) {
            return firstNonBlank(
                    referenceResolver.extractFirstString(record, "rejectionReason"),
                    "Execution was rejected by current beta policy."
            );
        }
        return firstNonBlank(
                nestedResultField(record, "error"),
                nestedResultField(record, "errorMessage"),
                "Execution failed and needs support inspection."
        );
    }

    private Map<String, Object> link(String label, String path) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("path", path);
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castItems(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    items.add((Map<String, Object>) map);
                }
            }
            return items;
        }
        return List.of();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
