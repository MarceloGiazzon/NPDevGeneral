package com.npdev.dsl.v1.gpucheck;

import java.util.List;
import java.util.Map;

/**
 * GPU-1 (G2.1): a line-by-line port of {@code reference_translators.py}'s {@code WgslEmitter} --
 * same structure, same output strings, so a WGSL snippet this emitter produces is byte-for-byte
 * what the Python reference (and so the numpy CPU twin, G3) would agree on. Turns a portable
 * expression tree (G1) into a boolean WGSL expression over one row's packed columns.
 *
 * <p>{@code __len} is this port's one addition beyond the reference file (which left it
 * unimplemented even though {@code wgsl-translation-rules.md}'s own table documents it for
 * minLength/maxLength): a {@code str_len}-encoded column's packed value already IS the UTF-16
 * length, so {@code __len(var f)} reinterprets that var's value in numeric position -- same
 * expression, "num" kind instead of "str".
 */
public final class GpuWgslEmitter {

    /** Mirrors the Python emitter's {@code (kind, valueExpr, nullExpr)} tuple; {@code enumValues}
     *  is set only when {@code kind.equals("enum")}. */
    public record Val(String kind, String expr, String nullExpr, List<String> enumValues) {
        Val(String kind, String expr, String nullExpr) {
            this(kind, expr, nullExpr, null);
        }
    }

    private final Map<String, Integer> indexByField;
    private final Map<String, GpuManifestColumn> columnByField;
    private final int scale;

    public GpuWgslEmitter(List<GpuManifestColumn> columnsInWordOrder, int packScale) {
        this.indexByField = new java.util.LinkedHashMap<>();
        this.columnByField = new java.util.LinkedHashMap<>();
        int i = 0;
        for (GpuManifestColumn col : columnsInWordOrder) {
            indexByField.put(col.field(), i);
            columnByField.put(col.field(), col);
            i++;
        }
        this.scale = packScale;
    }

    @SuppressWarnings("unchecked")
    public Val value(Map<String, Object> node) {
        String k = str(node.get("k"));
        if ("lit".equals(k)) {
            String t = str(node.get("t"));
            if ("num".equals(t)) {
                String scaled = GpuCheckSupport.scaledIntLiteral(str(node.get("v")), scale);
                return new Val("num", scaled, "false");
            }
            if ("bool".equals(t)) {
                boolean b = Boolean.TRUE.equals(node.get("v"));
                return new Val("bool", b ? "true" : "false", "false");
            }
            if ("str".equals(t)) {
                return new Val("strlit", str(node.get("v")), "false");
            }
            return new Val("null", "", "true");
        }
        if ("var".equals(k)) {
            String name = str(node.get("name"));
            int i = indexByField.get(name);
            GpuManifestColumn col = columnByField.get(name);
            String nullExpr = "isNull(base, " + i + "u)";
            String enc = col.encoding();
            if ("i32".equals(enc) || "fixed32".equals(enc)) {
                return new Val("num", "i(base, " + (i + 1) + "u)", nullExpr);
            }
            if ("bool".equals(enc)) {
                return new Val("bool", "(u(base, " + (i + 1) + "u) != 0u)", nullExpr);
            }
            if ("enum_index".equals(enc)) {
                return new Val("enum", "u(base, " + (i + 1) + "u)", nullExpr, col.enumValues());
            }
            return new Val("str", "u(base, " + (i + 1) + "u)", nullExpr);
        }
        if ("un".equals(k) && "-".equals(str(node.get("op")))) {
            Val a = value((Map<String, Object>) node.get("a"));
            return new Val("num", "(-" + a.expr() + ")", "false");
        }
        if ("bin".equals(k)) {
            String op = str(node.get("op"));
            if ("+".equals(op) || "-".equals(op)) {
                Val l = value((Map<String, Object>) node.get("l"));
                Val r = value((Map<String, Object>) node.get("r"));
                return new Val("num", "(" + l.expr() + " " + op + " " + r.expr() + ")", "false");
            }
        }
        if ("call".equals(k) && "__len".equals(str(node.get("name")))) {
            List<Object> args = (List<Object>) node.get("args");
            Val a = value((Map<String, Object>) args.get(0));
            // Every number in a pack is compared at the pack's decimal scale (num literals are
            // rescaled above, i32/fixed32 columns are packed rescaled) -- a str_len word is packed as
            // the raw length, so it must be rescaled here or maxLength 5 becomes "<= 500".
            String expr = scale == 0 ? a.expr()
                    : "(" + a.expr() + " * " + java.math.BigInteger.TEN.pow(scale) + "u)";
            return new Val("num", expr, a.nullExpr());
        }
        return new Val("bool", booleanExpr(node), "false");
    }

    @SuppressWarnings("unchecked")
    public String booleanExpr(Map<String, Object> node) {
        String k = str(node.get("k"));
        if ("call".equals(k)) {
            String name = str(node.get("name"));
            List<Object> args = (List<Object>) node.get("args");
            if ("__notNull".equals(name)) {
                return "(!" + value((Map<String, Object>) args.get(0)).nullExpr() + ")";
            }
            if ("__nullOr".equals(name)) {
                return "(" + value((Map<String, Object>) args.get(0)).nullExpr() + " || "
                        + booleanExpr((Map<String, Object>) args.get(1)) + ")";
            }
            if ("__inEnum".equals(name)) {
                return "(" + value((Map<String, Object>) args.get(0)).expr() + " != 0xFFFFFFFFu)";
            }
            throw new GpuUnsupportedException("uses the function '" + name + "', which only the app can evaluate");
        }
        if ("un".equals(k) && "!".equals(str(node.get("op")))) {
            return "(!" + booleanExpr((Map<String, Object>) node.get("a")) + ")";
        }
        if ("var".equals(k) || "lit".equals(k)) {
            Val v = value(node);
            if ("bool".equals(v.kind()) && !"false".equals(v.nullExpr())) {
                return "(!" + v.nullExpr() + " && " + v.expr() + ")";
            }
            return "bool".equals(v.kind()) ? v.expr() : "false";
        }
        if ("bin".equals(k)) {
            String op = str(node.get("op"));
            Map<String, Object> left = (Map<String, Object>) node.get("l");
            Map<String, Object> right = (Map<String, Object>) node.get("r");
            if ("&&".equals(op) || "||".equals(op)) {
                return "(" + booleanExpr(left) + " " + op + " " + booleanExpr(right) + ")";
            }
            if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
                return "(" + value(left).expr() + " " + op + " " + value(right).expr() + ")";
            }
            if ("==".equals(op) || "!=".equals(op)) {
                String eq = equalsExpr(left, right);
                return "==".equals(op) ? eq : ("(!" + eq + ")");
            }
        }
        throw new GpuUnsupportedException("unsupported node shape at WGSL emission: " + node);
    }

    public String equalsExpr(Map<String, Object> left, Map<String, Object> right) {
        Val l = value(left);
        Val r = value(right);
        if ("null".equals(l.kind())) {
            return "(" + r.nullExpr() + ")";
        }
        if ("null".equals(r.kind())) {
            return "(" + l.nullExpr() + ")";
        }
        if ("strlit".equals(r.kind()) && "enum".equals(l.kind())) {
            List<String> values = l.enumValues();
            int idx = values.indexOf(r.expr());
            String target = idx >= 0 ? (idx + "u") : "0xFFFFFFFEu";
            return "(!" + l.nullExpr() + " && (" + l.expr() + " == " + target + "))";
        }
        if ("strlit".equals(l.kind()) && "enum".equals(r.kind())) {
            return equalsExpr(right, left);
        }
        if ("strlit".equals(r.kind()) && "str".equals(l.kind())) {
            if (!r.expr().isEmpty()) {
                throw new GpuUnsupportedException("string comparison other than == ''");
            }
            return "(!" + l.nullExpr() + " && (" + l.expr() + " == 0u))";
        }
        return "((" + l.nullExpr() + " && " + r.nullExpr() + ") || (!" + l.nullExpr() + " && !" + r.nullExpr()
                + " && (" + l.expr() + " == " + r.expr() + ")))";
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
