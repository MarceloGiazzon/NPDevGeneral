package com.finalexec;

import com.finalexec.boundary.BoundaryBootException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
        basePackages = {
        "com.finalexec",
        "com.npdev.generated"
},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.npdev\\.generated\\.runtime\\.NPDevRuntimeApplication"
        )
)
public class FinalExecApplication {

    /** Item 6, SUPPORT_FEATURES_PLAN_2026-08-26: a boundary boot refusal (B4 migration-lock
     *  timeout, B5 schema-ahead, B9, B10) is a designed limit, not a bug -- under any container
     *  orchestrator, letting it fall through to the JVM's default uncaught-exception handling makes
     *  it LOOK like a crash loop (a raw stack trace, the same generic exit code a real bug gets).
     *  Distinct from 0 (success), 1 (the default uncaught-exception code -- a genuine bug still gets
     *  it, see the rethrow below), and the -ImpactOnly report codes 0/2/3
     *  ({@code SchemaLifecycleExecutor#reportOnlyExitCode}), a wholly different CLI mode. */
    static final int BOUNDARY_BOOT_REFUSAL_EXIT_CODE = 4;

    public static void main(String[] args) {
        try {
            SpringApplication.run(FinalExecApplication.class, args);
        } catch (RuntimeException failure) {
            if (findBoundaryBootException(failure) == null) {
                throw failure; // an unrecognized failure keeps its full stack trace -- it IS a bug
            }
            // The clean description/action was already printed by
            // BoundaryBootExceptionFailureAnalyzer (see its javadoc) -- Spring Boot's own failure
            // reporting ran before this catch block does, and suppressed the "Application run
            // failed" stack-trace log in favor of that analysis. This catch exists only to stop the
            // exception reaching the JVM's default top-level handler (which would otherwise print
            // "Exception in thread main" plus a second, raw stack trace) and to choose the distinct
            // exit code above.
            System.exit(BOUNDARY_BOOT_REFUSAL_EXIT_CODE);
        }
    }

    static BoundaryBootException findBoundaryBootException(Throwable failure) {
        Throwable current = failure;
        int guard = 0;
        while (current != null && guard++ < 32) {
            if (current instanceof BoundaryBootException boundaryBootException) {
                return boundaryBootException;
            }
            current = current.getCause();
        }
        return null;
    }
}
