package com.finalexec.config;

import com.npdev.adapters.resumebootstrap.spring.ResumeBootstrapRunner;
import com.npdev.adapters.resumebootstrap.spring.ResumeSchedulerRunner;
import com.npdev.adapters.resumebootstrap.spring.ScheduledEventDrainRunner;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NpdevSchedulerConfig {

    @Bean
    public SchedulerRuntimeState schedulerRuntimeState() {
        return new SchedulerRuntimeState();
    }

    @Bean
    public ResumeBootstrapRunner resumeBootstrapRunner(
            KernelRunner kernelRunner,
            RuntimeSettings runtimeSettings
    ) {
        return new ResumeBootstrapRunner(kernelRunner, runtimeSettings.schedulerBatchLimit());
    }

    @Bean
    public ResumeSchedulerRunner resumeSchedulerRunner(
            KernelRunner kernelRunner,
            RuntimeSettings runtimeSettings,
            SchedulerRuntimeState schedulerRuntimeState,
            MetricsSink metricsSink,
            PermissionEvaluator permissionEvaluator
    ) {
        return new ResumeSchedulerRunner(
                kernelRunner,
                runtimeSettings.schedulerBatchLimit(),
                runtimeSettings.schedulerEnabled(),
                schedulerRuntimeState,
                metricsSink
        );
    }

    /**
     * Drains the durable scheduled-event table on the same tick as the resume sweep above.
     *
     * <p>The method reference is bound here rather than inside the adapter because
     * {@code processDueScheduledEvents} lives in {@code :adapters:runtime-support} while the runner
     * lives in {@code :adapters:resume-bootstrap-spring}; the RuntimeHost is the one place that
     * already has both. See {@link ScheduledEventDrainRunner} for why nothing polled this table
     * until now.
     */
    @Bean
    public ScheduledEventDrainRunner scheduledEventDrainRunner(
            GeneratedCrudRuntimeSupport runtimeSupport,
            RuntimeSettings runtimeSettings,
            MetricsSink metricsSink
    ) {
        return new ScheduledEventDrainRunner(
                runtimeSupport::processDueScheduledEvents,
                runtimeSettings.schedulerBatchLimit(),
                runtimeSettings.schedulerEnabled(),
                metricsSink
        );
    }
}
