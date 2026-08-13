package com.finalexec.config;

import com.npdev.adapters.resumebootstrap.spring.ResumeBootstrapRunner;
import com.npdev.adapters.resumebootstrap.spring.ResumeSchedulerRunner;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
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
}
