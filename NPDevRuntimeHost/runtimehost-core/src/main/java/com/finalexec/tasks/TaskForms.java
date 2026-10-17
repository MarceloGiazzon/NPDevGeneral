package com.finalexec.tasks;

import com.npdev.dsl.v1.compiled.CompiledHumanTask;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROC-2: validates and coerces the values a person typed into a task form against the task's
 * declared fields. Undeclared keys are DROPPED, so a caller can never smuggle extra keys into the
 * event payload the flow receives.
 */
final class TaskForms {

    private TaskForms() {
    }

    static Map<String, Object> validateAndCoerce(CompiledHumanTask task, Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> input = values == null ? Map.of() : values;
        for (CompiledHumanTask.Field field : task.getFields()) {
            Object raw = input.get(field.name());
            boolean missing = raw == null || (raw instanceof String s && s.isBlank());
            if (missing) {
                if (field.required()) {
                    throw invalid(field, "is required");
                }
                continue;
            }
            out.put(field.name(), coerce(field, raw));
        }
        return out;
    }

    private static Object coerce(CompiledHumanTask.Field field, Object raw) {
        String type = field.type() == null ? "string" : field.type();
        try {
            switch (type) {
                case "integer":
                    if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                        return n.longValue();
                    }
                    return Long.parseLong(String.valueOf(raw).trim());
                case "decimal":
                    return raw instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(raw).trim());
                case "boolean":
                    if (raw instanceof Boolean b) {
                        return b;
                    }
                    String text = String.valueOf(raw).trim();
                    if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                        return Boolean.parseBoolean(text);
                    }
                    throw invalid(field, "must be true or false");
                case "date":
                    return LocalDate.parse(String.valueOf(raw).trim()).toString();
                case "string":
                case "text":
                default:
                    String value = String.valueOf(raw);
                    if (field.maxLength() != null && value.length() > field.maxLength()) {
                        throw invalid(field, "must be at most " + field.maxLength() + " characters");
                    }
                    return value;
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw invalid(field, "is not a valid " + type);
        }
    }

    private static HumanTaskService.TaskException invalid(CompiledHumanTask.Field field, String reason) {
        return new HumanTaskService.TaskException("TASK_FIELD_INVALID", field.name() + ": " + reason);
    }
}
