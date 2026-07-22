package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.INTEGER;
import static com.npdev.kernel.dbschema.InternalColumnType.JSON_DOCUMENT;
import static com.npdev.kernel.dbschema.InternalColumnType.LARGE_TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

public final class NpdevFlowInstanceTable {
    public static final String NAME = "npdev_flow_instance";

    private NpdevFlowInstanceTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("execution_id", TEXT),
                        InternalColumnDefinition.required("flow_name", TEXT),
                        InternalColumnDefinition.required("correlation_id", TEXT),
                        InternalColumnDefinition.optional("tenant_id", TEXT),
                        InternalColumnDefinition.optional("actor_id", TEXT),
                        InternalColumnDefinition.required("status", TEXT),
                        InternalColumnDefinition.required("current_step_index", INTEGER),
                        InternalColumnDefinition.optional("waiting_for_event_name", TEXT),
                        InternalColumnDefinition.required("state_json", JSON_DOCUMENT),
                        InternalColumnDefinition.required("created_at", TIMESTAMP),
                        InternalColumnDefinition.required("updated_at", TIMESTAMP),
                        InternalColumnDefinition.defaulted("resume_attempt_count", INTEGER, "0"),
                        InternalColumnDefinition.optional("last_resume_at", TIMESTAMP),
                        InternalColumnDefinition.optional("last_resume_error_code", TEXT),
                        InternalColumnDefinition.optional("next_eligible_resume_at", TIMESTAMP),
                        InternalColumnDefinition.optional("last_progress_at", TIMESTAMP),
                        InternalColumnDefinition.optional("last_error_kind", TEXT),
                        InternalColumnDefinition.optional("last_error_code", TEXT),
                        InternalColumnDefinition.optional("last_error_message", LARGE_TEXT),
                        InternalColumnDefinition.optional("failed_at", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("execution_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_flow_instance_correlation", "correlation_id"),
                        InternalIndexDefinition.index("idx_npdev_flow_instance_waiting_event", "waiting_for_event_name"),
                        InternalIndexDefinition.index("idx_npdev_flow_instance_status_updated", "status", "updated_at", "execution_id"),
                        InternalIndexDefinition.index("idx_npdev_flow_instance_tenant", "tenant_id"),
                        InternalIndexDefinition.index("idx_inst_tenant_updated", "tenant_id", "updated_at"),
                        InternalIndexDefinition.index("idx_inst_tenant_status_updated", "tenant_id", "status", "updated_at"),
                        InternalIndexDefinition.index("idx_inst_tenant_corr", "tenant_id", "correlation_id"),
                        InternalIndexDefinition.index("idx_inst_tenant_next_eligible", "tenant_id", "next_eligible_resume_at", "updated_at"),
                        InternalIndexDefinition.index("idx_inst_tenant_last_progress", "tenant_id", "last_progress_at"),
                        InternalIndexDefinition.index("idx_inst_tenant_status_failed_at", "tenant_id", "status", "failed_at"),
                        InternalIndexDefinition.index("idx_inst_tenant_last_error_code", "tenant_id", "last_error_code")
                )
        );
    }
}
