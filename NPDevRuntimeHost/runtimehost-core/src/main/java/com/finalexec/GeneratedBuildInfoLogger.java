package com.finalexec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

/**
 * Logs NPDev generator build-info when FinalExec starts.
 *
 * Load order:
 * 1) classpath:/npdev-build-info.properties
 * 2) <project>/build/npdev-generated/src/main/resources/npdev-build-info.properties
 * 3) <project>/npdev-generated/src/main/resources/npdev-build-info.properties
 *
 * This keeps FinalExec fully self-contained: it never reads outside its own project folder.
 */
@Component
public class GeneratedBuildInfoLogger {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratedBuildInfoLogger.class);

    private static final String BUILD_INFO_NAME = "npdev-build-info.properties";

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);

        BuildInfo info = readBuildInfo();

        LOG.info(
                "[NPDev] FinalExec started at {} | generatedAtUtc={} | tag={} | source={}",
                nowUtc,
                info.generatedAtUtc,
                info.tag,
                info.source
        );
    }

    private BuildInfo readBuildInfo() {
        // 1) Try classpath first
        ClassPathResource res = new ClassPathResource(BUILD_INFO_NAME);
        if (res.exists()) {
            try (InputStream in = res.getInputStream()) {
                Properties p = new Properties();
                p.load(in);
                return BuildInfo.from(p, "classpath:/" + BUILD_INFO_NAME);
            } catch (Exception e) {
                return BuildInfo.unknown("classpath error: " + e.getClass().getSimpleName());
            }
        }

        // 2) Fallback to copied/generated file paths inside this project
        Path projectRoot = Path.of(System.getProperty("user.dir"));

        List<Path> candidates = List.of(
                projectRoot.resolve("build").resolve("npdev-generated").resolve("src").resolve("main").resolve("resources").resolve(BUILD_INFO_NAME),
                projectRoot.resolve("npdev-generated").resolve("src").resolve("main").resolve("resources").resolve(BUILD_INFO_NAME)
        );

        for (Path p : candidates) {
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    Properties props = new Properties();
                    props.load(in);
                    return BuildInfo.from(props, p.toString());
                } catch (Exception e) {
                    return BuildInfo.unknown("file error: " + e.getClass().getSimpleName());
                }
            }
        }

        return BuildInfo.unknown("MISSING");
    }

    private static final class BuildInfo {
        final String tag;
        final String generatedAtUtc;
        final String source;

        private BuildInfo(String tag, String generatedAtUtc, String source) {
            this.tag = (tag == null || tag.isBlank()) ? "UNKNOWN" : tag;
            this.generatedAtUtc = (generatedAtUtc == null || generatedAtUtc.isBlank()) ? "UNKNOWN" : generatedAtUtc;
            this.source = (source == null || source.isBlank()) ? "UNKNOWN" : source;
        }

        static BuildInfo from(Properties p, String source) {
            String tag = p.getProperty("npdev.generator.tag", "UNKNOWN").trim();
            String gen = p.getProperty("npdev.generator.generatedAtUtc", "UNKNOWN").trim();
            return new BuildInfo(tag, gen, source);
        }

        static BuildInfo unknown(String reason) {
            return new BuildInfo("UNKNOWN(" + reason + ")", "UNKNOWN", reason);
        }
    }
}