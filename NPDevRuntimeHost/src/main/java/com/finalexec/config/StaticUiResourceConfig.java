package com.finalexec.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * REG-109 fix (Fast Lane plan item 1b): the generated static UI bundle
 * ({@code static/shell.js}, {@code static/npdev-business-ui/{index.html,app.js,style.css,
 * generated-ui-manifest.json}}) had no external-path override, unlike {@code NPDevModelProvider}'s
 * {@code compiled-model.json} (LC-C2) or {@code RuntimeMetadataService}'s catalogs (REG-103) --
 * confirmed by REG-109's own filing: no {@code WebMvcConfigurer.addResourceLocations} override
 * existed anywhere in this module, so the bundle was reachable only from the packaged jar's
 * classpath.
 *
 * <p>Mirrors the SAME "external directory wins, classpath is the fallback" precedent those two
 * already established, applied to Spring's static-resource pipeline instead of a
 * {@code ClassPathResource} load: an external {@code file:} location is registered BEFORE the
 * default classpath locations Spring Boot's own autoconfiguration would otherwise register (this
 * class registering a handler for {@code /**} makes {@code WebMvcAutoConfiguration} back off, per
 * {@code ResourceHandlerRegistry#hasMappingForPattern}) -- so overwriting a file under the external
 * directory and either reloading the resource cache or restarting the JVM is enough to serve new
 * content, no rebuild required, same shape as the compiled-model/compiled-metadata fast path.
 *
 * <p>Confirmed (not assumed) before writing this: {@code business-ui-index.mustache}/
 * {@code business-ui-app.mustache} reference these assets by plain relative URL
 * ({@code /shell.js}, {@code ./app.js}, {@code ./generated-ui-manifest.json}) -- no content hash, no
 * cache-busting query string -- so an override-directory fix does not need to rewrite any HTML/JS
 * reference, only the resource-location resolution order. {@code npdev.static-ui.path} defaults to
 * the SAME {@code npdev-generated/src/main/resources/static} relative layout the generator already
 * writes to (mirroring {@code RuntimeMetadataService}'s {@code npdev.generated-resources.path}
 * default), so an unconfigured app's behaviour is unchanged in effect: the external directory
 * already holds a byte-identical copy of what is baked into the jar until something writes fresh
 * content there.
 */
@Configuration
public class StaticUiResourceConfig implements WebMvcConfigurer {

    private static final String STATIC_UI_PATH_DEFAULT = "npdev-generated/src/main/resources/static";

    // The same classpath locations Spring Boot's own WebMvcAutoConfiguration registers for "/**" by
    // default (WebProperties.Resources.CLASSPATH_RESOURCE_LOCATIONS) -- kept identical so replacing
    // the "/**" handler here does not silently drop coverage for any of them.
    private static final List<String> DEFAULT_CLASSPATH_LOCATIONS = List.of(
            "classpath:/META-INF/resources/",
            "classpath:/resources/",
            "classpath:/static/",
            "classpath:/public/"
    );

    private final String staticUiPath;

    public StaticUiResourceConfig(
            @Value("${npdev.static-ui.path:" + STATIC_UI_PATH_DEFAULT + "}") String staticUiPath
    ) {
        this.staticUiPath = staticUiPath;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        List<String> locations = new ArrayList<>();
        locations.add("file:" + externalLocation() + "/");
        locations.addAll(DEFAULT_CLASSPATH_LOCATIONS);
        registry.addResourceHandler("/**").addResourceLocations(locations.toArray(new String[0]));
    }

    private String externalLocation() {
        Path resolved = Paths.get(staticUiPath).toAbsolutePath().normalize();
        return resolved.toString().replace('\\', '/');
    }
}
