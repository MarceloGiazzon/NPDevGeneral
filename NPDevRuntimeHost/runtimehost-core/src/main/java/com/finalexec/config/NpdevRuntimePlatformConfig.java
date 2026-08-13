package com.finalexec.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@PropertySource(value = "classpath:npdev-runtime-actuator.properties", ignoreResourceNotFound = true)
public class NpdevRuntimePlatformConfig {
}
