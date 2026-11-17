package com.npdev.dsl.v1.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * FORM P3-T2: a static type check of one expression, run by model validation in every context of the
 * expression-language context table. It reports only DEFINITE mistakes -- an operand whose type is known
 * and cannot work (a date in {@code *}, a list on the right of {@code ==}'s cousin {@code in}, a text
 * ordered with {@code <}, the wrong argument count for a stdlib function) -- and treats everything it
 * cannot resolve as {@link ExprType#ANY}, so a valid model is never refused on a guess.
 *
 * <p>Unknown function NAMES are not reported here (the value-behavior validator, the invariant engine and
 * the capability layer each own their own function set); in a {@code pushable} context, which is lowered
 * to SQL, any call that is not a {@code sql: true} stdlib function and any lambda IS reported.
 */
public final class ExpressionTypeChecker {

    /** What the context needs the whole expression to be. */
    public enum Expected { ANY, BOOLEAN, NUMBER }

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ISO_DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*$");

    private ExpressionTypeChecker() {
    }

    /**
     * @param scope    the type of a field/variable reference (dotted paths whole, {@code $user.id} included),
     *                 or null when unresolved -- which is ANY, never an error
     * @param pushable the context is lowered to SQL (access.read, queries[].where, widget aggregates)
     * @return human-readable problems, each naming the offending sub-expression; empty when none, or when
     *         the expression does not parse (syntax is reported elsewhere)
     */
    public static List<String> check(String expression, Function<String, ExprType> scope, Expected expected, boolean pushable) {
        ComputedExpression.Node root;
        try {
            root = ComputedExpression.parse(expression);
        } catch (ComputedExpression.ExpressionException syntax) {
            return List.of();
        }
        Session session = new Session(scope, pushable);
        ExprType type = root.typeOf(session);
        if (expected == Expected.BOOLEAN && !type.fits(ExprType.BOOLEAN)) {
            session.errors.add("`" + root.render() + "`: must evaluate to a boolean here but is " + type.label());
        }
        if (expected == Expected.NUMBER && !type.fits(ExprType.NUMBER)) {
            session.errors.add("`" + root.render() + "`: must evaluate to a number here but is " + type.label());
        }
        return List.copyOf(session.errors);
    }

    /** The mutable walk state handed to {@link ComputedExpression.Node#typeOf}. */
    public static final class Session {
        private final Function<String, ExprType> scope;
        private final boolean pushable;
        private final List<String> errors = new ArrayList<>();
        private final Deque<String> lambdaParams = new ArrayDeque<>();

        private Session(Function<String, ExprType> scope, boolean pushable) {
            this.scope = scope == null ? name -> null : scope;
            this.pushable = pushable;
        }

        private void error(ComputedExpression.Node at, String message) {
            String text = "`" + at.render() + "`: " + message;
            if (!errors.contains(text)) {
                errors.add(text);
            }
        }

        ExprType literal(Object value) {
            if (value == null) {
                return ExprType.NULL;
            }
            if (value instanceof Boolean) {
                return ExprType.BOOLEAN;
            }
            if (value instanceof Number) {
                return ExprType.NUMBER;
            }
            if (value instanceof String text) {
                String t = text.trim();
                if (ISO_DATE.matcher(t).matches()) {
                    return ExprType.DATE;
                }
                return ISO_DATE_TIME.matcher(t).matches() ? ExprType.DATETIME : ExprType.TEXT;
            }
            return ExprType.ANY;
        }

        ExprType variable(String name) {
            for (String param : lambdaParams) {
                if (name.equals(param) || name.startsWith(param + ".")) {
                    return ExprType.ANY;
                }
            }
            ExprType type = scope.apply(name);
            return type == null ? ExprType.ANY : type;
        }

        ExprType unary(ComputedExpression.Node at, String op, ComputedExpression.Node operand, ExprType type) {
            if ("!".equals(op)) {
                return ExprType.BOOLEAN;
            }
            requireNumber(at, operand, type);
            return ExprType.NUMBER;
        }

        private void requireNumber(ComputedExpression.Node at, ComputedExpression.Node operand, ExprType type) {
            switch (type) {
                case BOOLEAN, DATE, DATETIME, LIST, TEXT ->
                        error(at, "operand `" + operand.render() + "` is " + type.label() + ", not a number");
                default -> { }
            }
        }

        ExprType binary(ComputedExpression.Node at, String op, ComputedExpression.Node left, ExprType l,
                        ComputedExpression.Node right, ExprType r) {
            switch (op) {
                case "&&", "||", "==", "!=":
                    return ExprType.BOOLEAN;
                case "??":
                    return unify(at, l, r, "both sides of ?? must have the same type");
                case "in", "not in":
                    if (!r.fits(ExprType.LIST)) {
                        error(at, "the right side of `" + op + "` must be a list but `" + right.render() + "` is " + r.label());
                    }
                    return ExprType.BOOLEAN;
                case "<", "<=", ">", ">=":
                    if (l == ExprType.BOOLEAN || l == ExprType.LIST || r == ExprType.BOOLEAN || r == ExprType.LIST) {
                        error(at, "a " + (l == ExprType.BOOLEAN || l == ExprType.LIST ? l : r).label() + " cannot be ordered with " + op);
                    } else if (l == ExprType.TEXT && r == ExprType.TEXT) {
                        error(at, "text cannot be ordered with " + op + " -- the engine orders numbers and dates only;"
                                + " compare numbers, or ISO dates such as '2026-01-31'");
                    }
                    return ExprType.BOOLEAN;
                case "+":
                    if (l == ExprType.NUMBER && r == ExprType.NUMBER) {
                        return ExprType.NUMBER;
                    }
                    if (l == ExprType.TEXT || r == ExprType.TEXT) {
                        return ExprType.TEXT;
                    }
                    if ((l.isTemporal() && r == ExprType.NUMBER) || (l == ExprType.NUMBER && r.isTemporal())) {
                        error(at, "adds a number to a date -- use addDays(date, n), addHours(datetime, n) or addMinutes(datetime, n)");
                        return ExprType.ANY;
                    }
                    if (l.isTemporal() && r.isTemporal()) {
                        error(at, "adds two dates");
                        return ExprType.ANY;
                    }
                    if ((l == ExprType.BOOLEAN && r == ExprType.NUMBER) || (l == ExprType.NUMBER && r == ExprType.BOOLEAN)) {
                        error(at, "adds a boolean and a number");
                        return ExprType.ANY;
                    }
                    return ExprType.ANY;
                case "-":
                    if (l.isTemporal() && r.isTemporal()) {
                        error(at, "subtracts dates -- use daysBetween(from, to) or minutesBetween(from, to)");
                        return ExprType.NUMBER;
                    }
                    requireNumber(at, left, l);
                    requireNumber(at, right, r);
                    return ExprType.NUMBER;
                case "*", "/", "%":
                    requireNumber(at, left, l);
                    requireNumber(at, right, r);
                    return ExprType.NUMBER;
                default:
                    return ExprType.ANY;
            }
        }

        ExprType ternary(ComputedExpression.Node at, ExprType condition, ExprType whenTrue, ExprType whenFalse) {
            if (!condition.fits(ExprType.BOOLEAN)) {
                error(at, "the condition before `?` must be a boolean but is " + condition.label());
            }
            return unify(at, whenTrue, whenFalse, "both branches of ?: must have the same type");
        }

        private ExprType unify(ComputedExpression.Node at, ExprType a, ExprType b, String message) {
            if (a == ExprType.NULL) {
                return b;
            }
            if (b == ExprType.NULL) {
                return a;
            }
            if (!a.fits(b)) {
                error(at, message + " (" + a.label() + " and " + b.label() + ")");
                return ExprType.ANY;
            }
            return a == ExprType.ANY ? b : a;
        }

        ExprType lambda(String param, ComputedExpression.Node body) {
            lambdaParams.push(param);
            try {
                body.typeOf(this);
            } finally {
                lambdaParams.pop();
            }
            return ExprType.ANY;
        }

        ExprType call(ComputedExpression.Node at, String name, List<ComputedExpression.Node> args) {
            List<ExprType> types = new ArrayList<>();
            boolean hasLambda = false;
            for (ComputedExpression.Node arg : args) {
                types.add(arg.typeOf(this));
                hasLambda |= arg.isLambda();
            }
            StdlibRegistry.Entry entry = StdlibRegistry.find(name).orElse(null);
            if (pushable && (entry == null || !entry.sql() || hasLambda)) {
                error(at, entry == null
                        ? name + "() is not a function this context can push down to the database"
                        : hasLambda ? name + "() takes a lambda, which cannot be pushed down to the database"
                                : name + "() cannot be pushed down to the database here");
            }
            if (entry == null || !"value".equals(entry.kind())) {
                return ExprType.ANY;
            }
            checkArguments(at, entry, args, types);
            return returnType(entry, types);
        }

        private void checkArguments(ComputedExpression.Node at, StdlibRegistry.Entry entry,
                                    List<ComputedExpression.Node> args, List<ExprType> types) {
            List<String> params = entry.params();
            boolean variadic = !params.isEmpty() && params.get(params.size() - 1).endsWith("...");
            int required = 0;
            for (String p : params) {
                if (!p.endsWith("?") && !p.endsWith("...")) {
                    required++;
                }
            }
            int max = variadic ? Integer.MAX_VALUE : params.size();
            if (args.size() < required || args.size() > max) {
                error(at, entry.name() + "() takes " + (variadic ? "at least " + required : String.valueOf(params.size()))
                        + " argument(s) but is given " + args.size());
                return;
            }
            for (int i = 0; i < args.size(); i++) {
                String spec = params.get(Math.min(i, params.size() - 1)).replace("...", "").replace("?", "");
                ExprType t = types.get(i);
                boolean numeric = spec.equals("decimal") || spec.equals("integer");
                boolean temporal = spec.equals("date") || spec.equals("datetime") || spec.equals("date|datetime");
                if (numeric && (t == ExprType.BOOLEAN || t.isTemporal() || t == ExprType.LIST)) {
                    error(at, "argument " + (i + 1) + " `" + args.get(i).render() + "` of " + entry.name() + "() must be a number but is " + t.label());
                } else if (temporal && (t == ExprType.NUMBER || t == ExprType.BOOLEAN || t == ExprType.LIST)) {
                    error(at, "argument " + (i + 1) + " `" + args.get(i).render() + "` of " + entry.name() + "() must be a date but is " + t.label());
                }
            }
        }

        private static ExprType returnType(StdlibRegistry.Entry entry, List<ExprType> argTypes) {
            return switch (entry.returns()) {
                case "integer", "decimal" -> ExprType.NUMBER;
                case "text", "uuid" -> ExprType.TEXT;
                case "boolean" -> ExprType.BOOLEAN;
                case "datetime" -> ExprType.DATETIME;
                case "date" -> ExprType.DATE;
                case "date|datetime" -> !argTypes.isEmpty() && argTypes.get(0).isTemporal() ? argTypes.get(0) : ExprType.ANY;
                default -> entry.returns().startsWith("list") ? ExprType.LIST : ExprType.ANY;
            };
        }
    }
}
