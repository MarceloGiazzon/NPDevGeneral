package com.npdev.dsl.v1.compiled;

/**
 * ENG-1: the ONE place that knows how a model author's {@code schedule.cron} maps onto what the
 * runtime scheduler (Spring 6 {@code CronExpression}/{@code CronTrigger}, which accepts ONLY the
 * 6-field seconds-first form) can parse. Shared by:
 * <ul>
 *   <li>{@code FlowValidation.validateFlowSchedule} -- field-count check (via {@link #fieldCount})</li>
 *   <li>{@code ModelCompiler.toCompiledFlowSchedule} -- stores the normalized form, so canonical JSON,
 *       the ControlPanel schedules endpoint and the scheduler all see 6 fields</li>
 *   <li>{@code NpdevCronSchedulerService.start} -- defensive re-normalization for compiled models
 *       produced before ENG-1 (old canonical JSON) and for tests that build {@code CompiledFlowSchedule}
 *       by hand</li>
 * </ul>
 * Pure string handling -- no Spring dependency (the dsl module has none).
 */
public final class CronSchedules {

    private CronSchedules() {
    }

    /** Number of whitespace-separated fields; 0 for null/blank. A Spring macro ("@daily") counts as 1. */
    public static int fieldCount(String cron) {
        if (cron == null || cron.isBlank()) {
            return 0;
        }
        return cron.trim().split("\\s+").length;
    }

    /** True for Spring's macro forms ({@code @yearly @annually @monthly @weekly @daily @midnight @hourly}). */
    public static boolean isMacro(String cron) {
        return cron != null && cron.trim().startsWith("@");
    }

    /**
     * Classic 5-field cron (minute hour day-of-month month day-of-week) becomes Spring's 6-field form
     * by prefixing a literal {@code 0} seconds field; 6-field and macro forms are returned trimmed with
     * whitespace collapsed to single spaces; anything else is returned trimmed and unchanged so the
     * validator / scheduler can report it.
     */
    public static String normalize(String cron) {
        if (cron == null) {
            return null;
        }
        String collapsed = cron.trim().replaceAll("\\s+", " ");
        if (isMacro(collapsed)) {
            return collapsed;
        }
        return fieldCount(collapsed) == 5 ? "0 " + collapsed : collapsed;
    }
}
