package com.finalexec.boundary;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Item 6, SUPPORT_FEATURES_PLAN_2026-08-26. A {@link BoundaryBootException} (B4 migration-lock
 * timeout, B5 schema-ahead, B9, B10) is a NAMED, understood refusal -- not a bug in NPDev -- but
 * without this analyzer, Spring Boot's default failure handling renders it exactly like an
 * unexpected crash: a full stack trace logged under "Application run failed". Under any container
 * orchestrator that reads as a crash loop, not "NPDev refused to boot on purpose."
 *
 * <p>Registered in {@code META-INF/spring.factories}. Returning a non-null {@link FailureAnalysis}
 * here makes Spring Boot's own failure reporting print ONLY this clean description/action pair
 * (via {@code LoggingFailureAnalysisReporter}) instead of the stack trace -- see
 * {@code FinalExecApplication#main} for the other half of this fix: preventing the JVM's own
 * top-level "Exception in thread main" print (which would otherwise print a SECOND, raw trace once
 * this exception is rethrown out of {@code SpringApplication.run()}) and choosing a distinct exit
 * code an orchestrator or script can key on instead of the generic uncaught-exception code 1.
 */
public class BoundaryBootExceptionFailureAnalyzer extends AbstractFailureAnalyzer<BoundaryBootException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BoundaryBootException cause) {
        BoundaryViolation violation = cause.getViolation();
        return new FailureAnalysis(
                "NPDev refused to boot: " + violation.message(),
                "This is a designed limit (boundary " + violation.boundaryId() + "), not a crash in NPDev "
                        + "itself. Run `npdev why " + violation.boundaryId()
                        + "` for the full explanation and workaround.",
                cause);
    }
}
