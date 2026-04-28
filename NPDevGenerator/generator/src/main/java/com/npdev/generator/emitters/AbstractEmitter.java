package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.*;

public abstract class AbstractEmitter {
    protected final TemplateEngine templates;
    protected final GeneratedSourceWriter writer;

    protected AbstractEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        this.templates = templates;
        this.writer = writer;
    }

    protected static List<Map<String, Object>> toFieldsView(List<CompiledField> fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompiledField f : fields) {
            Map<String, Object> m = new HashMap<>();
            String name = f.getName();
            m.put("name", name);
            m.put("javaType", f.getJavaType());
            m.put("dslType", f.getDslType());
            m.put("required", f.isRequired());
            m.put("id", f.isId());
            m.put("capName", capitalize(name));
            out.add(m);
        }
        return out;
    }

    protected static Map<String, Object> idFieldView(List<CompiledField> fields) {
        for (CompiledField f : fields) {
            if (f.isId()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", f.getName());
                m.put("javaType", f.getJavaType());
                m.put("capName", capitalize(f.getName()));
                m.put("uuid", "java.util.UUID".equals(f.getJavaType()));
                return m;
            }
        }
        // default fallback (keeps generator robust)
        Map<String, Object> m = new HashMap<>();
        m.put("name", "id");
        m.put("javaType", "java.util.UUID");
        m.put("capName", "Id");
        m.put("uuid", true);
        return m;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toUpperCase(Locale.ROOT);
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}