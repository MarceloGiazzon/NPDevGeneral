package com.finalexec;

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

    public static void main(String[] args) {
        SpringApplication.run(FinalExecApplication.class, args);
    }
}
