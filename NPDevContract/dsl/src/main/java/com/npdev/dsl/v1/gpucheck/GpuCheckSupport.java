package com.npdev.dsl.v1.gpucheck;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPU-1 (G2.1): decides whether a portable expression tree (G1's
 * {@code ComputedExpression.toPortableTree}) is GPU/CPU-twin translatable, and which decimal
 * scale it needs. A line-by-line port of {@code reference_translators.py}'s {@code analyze()} --
 * same structure, same reasons, so a check marked {@code skipped} here and there names the same
 * cause. Throws {@link GpuUnsupportedException} rather than approximating: a check this translator
 * cannot reproduce EXACTLY is skipped, never guessed at.
 */
public final class GpuCheckSupport {

    private static final Set<String> BOOL_SHAPED_OPS = Set.of("==", "!=", "<", "<=", ">", ">=", "&&", "||");
    private static final Set<String> SUPPORTED_ENCODINGS = Set.of("bool", "i32", "fixed32", "enum_index", "str_len");

    private GpuCheckSupport() {
    }

    /** Returns the decimal scale this tree needs (0 for ints only), or throws
     *  {@link GpuUnsupportedException}. {@code columns}: field name -> encoding/scale/enumValues. */
    @SuppressWarnings("unchecked")
    public static int analyze(Map<String, Object> tree, Map<String, GpuColumnInfo> columns) {
        String k = str(tree.get("k"));
        boolean topLevelOk = ("un".equals(k) && "!".equals(str(tree.get("op"))))
                || ("bin".equals(k) && BOOL_SHAPED_OPS.contains(str(tree.get("op"))))
                || ("call".equals(k) && str(tree.get("name")) != null && str(tree.get("name")).startsWith("__"));
        if (!topLevelOk) {
            throw new GpuUnsupportedException("the expression's top level is not a comparison or logical "
                    + "operator (the app throws when such an expression yields null)");
        }
        int[] scale = {0};
        walk(tree, columns, scale);
        return scale[0];
    }

    @SuppressWarnings("unchecked")
    private static void walk(Map<String, Object> node, Map<String, GpuColumnInfo> columns, int[] scale) {
        String k = str(node.get("k"));
        switch (k) {
            case "lit" -> {
                if ("num".equals(str(node.get("t")))) {
                    scale[0] = Math.max(scale[0], decimalsOf(str(node.get("v"))));
                }
            }
            case "var" -> {
                String name = str(node.get("name"));
                if (name == null || name.indexOf('.') >= 0 || name.startsWith("$")) {
                    throw new GpuUnsupportedException(
                            "reads '" + name + "', which is not a plain field of this record");
                }
                GpuColumnInfo col = columns.get(name);
                if (col == null || !SUPPORTED_ENCODINGS.contains(col.encoding())) {
                    throw new GpuUnsupportedException("reads '" + name + "', whose type has no GPU encoding");
                }
                if ("fixed32".equals(col.encoding())) {
                    scale[0] = Math.max(scale[0], col.scale());
                }
            }
            case "un" -> walk((Map<String, Object>) node.get("a"), columns, scale);
            case "bin" -> {
                String op = str(node.get("op"));
                if ("*".equals(op) || "/".equals(op) || "%".equals(op)) {
                    throw new GpuUnsupportedException(
                            "uses '" + op + "', which the app computes with fractions/doubles");
                }
                walk((Map<String, Object>) node.get("l"), columns, scale);
                walk((Map<String, Object>) node.get("r"), columns, scale);
            }
            case "call" -> {
                String name = str(node.get("name"));
                if (name == null || !name.startsWith("__")) {
                    throw new GpuUnsupportedException(
                            "uses the function '" + name + "', which only the app can evaluate");
                }
                for (Object arg : (List<Object>) node.get("args")) {
                    walk((Map<String, Object>) arg, columns, scale);
                }
            }
            default -> throw new GpuUnsupportedException("unknown node kind " + k);
        }
    }

    static int decimalsOf(String literal) {
        if (literal == null) {
            return 0;
        }
        int dot = literal.indexOf('.');
        return dot < 0 ? 0 : literal.length() - dot - 1;
    }

    /** Rescales a literal's decimal-string value to {@code targetScale}, as an integer string --
     *  shared by {@link GpuWgslEmitter} (the i32 literal text) and the manifest builder (nowhere
     *  else needs it, but keeping it here keeps the scaling arithmetic in one place). */
    static String scaledIntLiteral(String decimalLiteral, int targetScale) {
        BigDecimal value = new BigDecimal(decimalLiteral);
        BigDecimal scaled = value.movePointRight(targetScale);
        return scaled.setScale(0, java.math.RoundingMode.UNNECESSARY).toBigInteger().toString();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
