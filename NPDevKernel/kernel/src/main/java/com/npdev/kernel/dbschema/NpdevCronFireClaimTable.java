package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;
import static com.npdev.kernel.dbschema.InternalColumnType.TIMESTAMP;

import java.util.List;

/**
 * R2.7: the cron-fire claim -- makes two (or more) scheduler instances polling ONE database safe
 * against double-firing the SAME scheduled tick of the SAME (flow, tenant) pair. Companion to
 * {@link NpdevFlowInstanceTable}'s R8c (RUN-2) resume claim, reusing the identical
 * claimed_by/claimed_until shape and the identical {@code SELECT ... FOR UPDATE SKIP LOCKED} +
 * lease pattern -- see {@code com.finalexec.scheduler.CronFireClaimStore} for the query side.
 *
 * <p>Unlike the resume claim, which narrows an already-existing batch of rows, a cron tick's claim
 * row does not exist ahead of time: there is exactly one legitimate fire attempt per
 * (flow_name, tenant_id, scheduled_fire_time), and nothing creates that row until the first
 * instance to observe the tick asks for it. {@code scheduled_fire_time} is computed independently
 * by each instance from the SAME cron expression via calendar math (never from observed wall-clock
 * "now"), so two instances racing the same logical tick compute the IDENTICAL key regardless of
 * scheduler-thread jitter -- see {@code NpdevCronSchedulerService#advanceFireTime}.
 */
public final class NpdevCronFireClaimTable {
    public static final String NAME = "npdev_cron_fire_claim";

    private NpdevCronFireClaimTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("flow_name", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("scheduled_fire_time", TIMESTAMP),
                        InternalColumnDefinition.required("created_at", TIMESTAMP),
                        // R2.7 (reusing RUN-2's exact shape): claimed_by identifies which scheduler
                        // instance currently holds this fire window (an opaque id, one per JVM --
                        // see CronFireClaimStore's claimant id); claimed_until is that hold's lease
                        // expiry. Both optional/nullable: the row is inserted unclaimed (both NULL)
                        // and only stamped once a claimant wins the SKIP LOCKED race.
                        InternalColumnDefinition.optional("claimed_by", TEXT),
                        InternalColumnDefinition.optional("claimed_until", TIMESTAMP)
                ),
                InternalPrimaryKeyDefinition.of("flow_name", "tenant_id", "scheduled_fire_time"),
                List.of()
        );
    }
}
