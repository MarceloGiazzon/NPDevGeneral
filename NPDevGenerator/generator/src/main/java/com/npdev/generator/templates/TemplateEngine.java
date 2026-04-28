package com.npdev.generator.templates;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class TemplateEngine {

    private static final char BOM = (char) 0xFEFF;

    private final MustacheFactory mf = new DefaultMustacheFactory();
    private final String basePath;

    public TemplateEngine(String basePath) {
        this.basePath = basePath;
    }

    public String render(String templateName, Map<String, Object> ctx) {
        String template = loadTemplate(templateName);

        Mustache mustache = mf.compile(new StringReader(template), templateName);
        // Deterministic template iteration: sort input keys first, then retain that order for Mustache traversal.
        Map<String, Object> orderedCtx = new LinkedHashMap<>();
        new TreeMap<>(ctx).forEach(orderedCtx::put);

        StringWriter sw = new StringWriter();
        mustache.execute(sw, orderedCtx);
        String out = sw.toString();

        if (!out.isEmpty() && out.charAt(0) == BOM) {
            out = out.substring(1);
        }
        return out;
    }

    private String loadTemplate(String templateName) {
        String full = basePath + templateName;
        InputStream in = TemplateEngine.class.getClassLoader().getResourceAsStream(full);
        if (in == null) {
            throw new IllegalArgumentException("Template not found on classpath: " + full);
        }

        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            String s = sb.toString();
            if (!s.isEmpty() && s.charAt(0) == BOM) {
                s = s.substring(1);
            }
            return s;
        } catch (IOException e) {
            throw new RuntimeException("Failed reading template: " + full, e);
        }
    }
}
