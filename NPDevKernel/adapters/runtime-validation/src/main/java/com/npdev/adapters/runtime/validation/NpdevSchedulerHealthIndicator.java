package com.npdev.adapters.runtime.validation;

import com.npdev.kernel.runtime.SchedulerRuntimeState;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public final class NpdevSchedulerHealthIndicator implements HealthIndicator {
    private final RuntimeSettings settings;
    private final SchedulerRuntimeState runtimeState;

    public NpdevSchedulerHealthIndicator(RuntimeSettings settings, SchedulerRuntimeState runtimeState) {
        this.settings = settings;
        this.runtimeState = runtimeState;
    }

    @Override
    public Health health() {
        if (settings == null) {
            return Health.unknown().withDetail("reason", "settings_missing").build();
        }
        if (!settings.schedulerEnabled()) {
            return Health.up()
                    .withDetail("schedulerEnabled", false)
                    .withDetail("status", "DISABLED")
                    .build();
        }
        if (runtimeState == null) {
            return Health.down()
                    .withDetail("schedulerEnabled", true)
                    .withDetail("reason", "scheduler_state_missing")
                    .build();
        }

        long now = System.currentTimeMillis();
        long lastTickAt = runtimeState.lastTickAtEpochMs();
        long threshold = Math.max((long) settings.schedulerTickMillis() * 2L, 2_000L);

        if (lastTickAt <= 0) {
            return Health.down()
                    .withDetail("schedulerEnabled", true)
                    .withDetail("status", "NOT_STARTED")
                    .withDetail("tickMillis", settings.schedulerTickMillis())
                    .build();
        }

        long lag = Math.max(0L, now - lastTickAt);
        Health.Builder builder = lag <= threshold ? Health.up() : Health.down();
        builder.withDetail("schedulerEnabled", true)
                .withDetail("tickMillis", settings.schedulerTickMillis())
                .withDetail("lastTickAtEpochMs", lastTickAt)
                .withDetail("lagMs", lag)
                .withDetail("lastOutcome", runtimeState.lastOutcome());

        String errorCode = runtimeState.lastErrorCode();
        if (errorCode != null && !errorCode.isBlank()) {
            builder.withDetail("lastErrorCode", errorCode);
        }

        return builder.build();
    }
}