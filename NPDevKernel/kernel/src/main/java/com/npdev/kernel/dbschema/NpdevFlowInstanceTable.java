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
                        InternalColumnDefinition.optional("failed_at", TIMESTAMP),
                        // R8c (RUN-2): the resume claim. claimed_by identifies which resumer instance
                        // currently holds this row (an opaque id, one per JVM -- see
                        // ResumeCoordinator's RESUMER_ID); claimed_until is that hold's lease expiry.
                        // Both optional/nullable: an unclaimed row (the overwhelming common case) has
                        // both NULL, and JdbcFlowInstanceStore's claim query treats
                        // "claimed_until IS NULL OR claimed_until < now()" as claimable, exactly
                        // mirroring how next_eligible_resume_at above already models "eligible now".
                        // Landing on an ALREADY-DEPLOYED database only became possible once
                        // appendInternalTableAdditiveIndexes (SchemaRealizationEmitter) gave internal
                        // tables the additive-INDEX migration path the columns alone don't need but
                        // the index below does.
                        InternalColumnDefinition.optional("claimed_by", TEXT),
                        InternalColumnDefinition.optional("claimed_until", TIMESTAMP)
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
                        InternalIndexDefinition.index("idx_inst_tenant_last_error_code", "tenant_id", "last_error_code"),
                        // R8c (RUN-2): the claim query filters on exactly these three columns, in
                        // this order (tenant_id first -- every query scopes by it; status next --
                        // narrows to WAITING_EVENT; claimed_until last -- the claim eligibility
                        // check). Without this, the resume-claim SELECT ... FOR UPDATE SKIP LOCKED
                        // would fall back to the broader idx_inst_tenant_status_updated index and
                        // scan every waiting row of a tenant to test claimed_until by hand.
                        InternalIndexDefinition.index("idx_inst_tenant_status_claimed", "tenant_id", "status", "claimed_until")
                )
        );
    }
}
