package com.npdev.dsl.v1.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free expression engine for AutoPanel computed columns
 * (ADR-0004 §L3 Tier-A). Supports numeric/string/boolean/null literals, field
 * identifiers (looked up in a variable map), arithmetic ({@code + - * / %}),
 * comparison ({@code == != < <= > >=}), logical ({@code && ||}), unary
 * ({@code - !}), and parentheses — the arithmetic the platform's invariant CEL
 * matcher and schema-expression helper cannot evaluate.
 *
 * <p>{@link #parse(String)} builds an AST and throws {@link ExpressionException}
 * on a syntax error (used for author-time validation). {@link #evaluate} is
 * lenient at runtime: an unknown/blank/non-numeric operand coerces to 0 for
 * arithmetic so a display column never crashes a page load.
 *
 * <p>Field references may be dotted paths ({@code cliente.tipo}) to reach nested
 * scope values; the scope map is looked up by the full dotted name first, then by
 * walking nested {@code Map} values segment by segment.
 *
 * <p>Null semantics: {@code null == null} is true, {@code null == <anything else>}
 * (including {@code ""}) is false. Arithmetic/relational operators treat a null
 * operand as 0, consistent with the existing lenient-coercion behavior for missing
 * fields. {@link #evaluateBoolean} is a strict entry point for invariant-style
 * usage: the top-level result must be a {@link Boolean} or it throws.
 */
public final class ComputedExpression {

    private ComputedExpression() {
    }

    /** Thrown when an expression cannot be parsed. */
    public static final class ExpressionException extends RuntimeException {
        public ExpressionException(String message) {
            super(message);
        }
    }

    /** A parsed expression node. */
    public interface Node {
        Object eval(Map<String, Object> vars);

        /** True if this node's operator always yields a boolean, without evaluating. */
        default boolean looksBoolean() {
            return false;
        }

        /** Collect all field/variable names referenced by this node (and its children). */
        default void collectFields(java.util.Set<String> out) {
        }

        /**
         * Collect the name of every function call anywhere in this node (and its children),
         * including inside a {@code Call}'s arguments and a {@code Lambda}'s body -- scope-prefixed
         * names (e.g. {@code "scope.exists"}) kept as declared. Used for expression-default risk
         * classification ({@code ExpressionBackfillRiskClassifier}, B2): a function call is the one
         * signal that an expression needs more than a pure same-row computation to evaluate.
         */
        default void collectFunctionCalls(java.util.Set<String> out) {
        }

        /**
         * REG-269: collect every ordered comparison ({@code < <= > >=}) in this node (and its
         * children) with a description of each operand, so a semantic validator can check the
         * operand TYPES the engine will compare (e.g. a date field against a number).
         */
        default void collectOrderedComparisons(List<OrderedComparison> out) {
        }

        /**
         * LNCH-15: invoke this node as a one-argument lambda (only {@code Lambda} overrides this
         * -- see its javadoc), binding {@code item} to the lambda's parameter for one evaluation
         * of its body. Lets a {@link FunctionRegistry} function (e.g. a quantifier) call a lambda
         * argument without needing to know the concrete AST node type.
         */
        default Object invokeLambda(Object item, Map<String, Object> outerVars) {
            throw new ExpressionException("not a lambda expression");
        }

        /**
         * LNCH-15: the dotted field-path text this node reads, if it is (or trivially wraps) a
         * single field reference -- only {@code Var} returns non-null. Lets a
         * {@link FunctionRegistry} function report a human-readable field path for an argument
         * without needing to know the concrete AST node type (e.g. {@code matches}'s receiver
         * argument, for UI failure highlighting).
         */
        default String describeFieldPath() {
            return null;
        }

        /** FORM P3-T2: this node as expression text, for error messages that name a sub-expression. */
        default String render() {
            return "?";
        }

        /** FORM P3-T2: the static type of this node (see {@link ExpressionTypeChecker}); ANY when it cannot be told. */
        default ExprType typeOf(ExpressionTypeChecker.Session session) {
            return ExprType.ANY;
        }

        /** FORM P3-T2: true only for a {@code param => body} lambda. */
        default boolean isLambda() {
            return false;
        }
    }

    /** Parse an expression into an AST, or throw {@link ExpressionException}. */
    public static Node parse(String expression) {
        return new Parser(tokenize(expression)).parseAll();
    }

    /** GPU-1 (G1.1): a JSON-able view of the parsed tree, for code outside this class that must
     *  translate an expression (the GPU check builder, {@code GpuCheckManifestBuilder}). Kinds:
     *  lit, var, un, bin, call, lambda. Numbers become plain decimal strings so no precision is lost. */
    public static Map<String, Object> toPortableTree(String expression) {
        return portable(parse(expression));
    }

    private static Map<String, Object> portable(Node node) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (node instanceof Literal literal) {
            Object v = literal.value();
            out.put("k", "lit");
            if (v == null) {
                out.put("t", "null");
                out.put("v", null);
            } else if (v instanceof Boolean b) {
                out.put("t", "bool");
                out.put("v", b);
            } else if (v instanceof Number n) {
                out.put("t", "num");
                out.put("v", new java.math.BigDecimal(n.toString()).toPlainString());
            } else {
                out.put("t", "str");
                out.put("v", v.toString());
            }
        } else if (node instanceof Var var) {
            out.put("k", "var");
            out.put("name", var.name());
        } else if (node instanceof Unary unary) {
            out.put("k", "un");
            out.put("op", unary.op());
            out.put("a", portable(unary.operand()));
        } else if (node instanceof Binary binary) {
            out.put("k", "bin");
            out.put("op", binary.op());
            out.put("l", portable(binary.left()));
            out.put("r", portable(binary.right()));
        } else if (node instanceof Call call) {
            out.put("k", "call");
            out.put("name", call.name());
            List<Object> args = new ArrayList<>();
            for (Node arg : call.args()) {
                args.add(portable(arg));
            }
            out.put("args", args);
        } else if (node instanceof ListLiteral list) {
            out.put("k", "list");
            List<Object> items = new ArrayList<>();
            for (Node item : list.items()) {
                items.add(portable(item));
            }
            out.put("items", items);
        } else if (node instanceof Ternary ternary) {
            out.put("k", "tern");
            out.put("c", portable(ternary.cond()));
            out.put("a", portable(ternary.whenTrue()));
            out.put("b", portable(ternary.whenFalse()));
        } else {
            out.put("k", "lambda");
        }
        return out;
    }

    /** Parse-only syntax check; throws {@link ExpressionException} if invalid. */
    public static void validate(String expression) {
        parse(expression);
    }

    /** Parse and evaluate against the given variables. */
    public static Object evaluate(String expression, Map<String, Object> vars) {
        return evaluate(expression, vars, FunctionRegistry.empty());
    }

    /**
     * LNCH-15: parse and evaluate with a pluggable set of named functions (e.g. {@code uuid()},
     * {@code field.matches(regex)}, {@code items.all(x => ...)}) available to {@code Call}/
     * {@code Lambda} nodes. See {@link FunctionRegistry}.
     */
    public static Object evaluate(String expression, Map<String, Object> vars, FunctionRegistry functions) {
        FunctionRegistry previous = CURRENT_FUNCTIONS.get();
        CURRENT_FUNCTIONS.set(functions == null ? FunctionRegistry.empty() : functions);
        try {
            return parse(expression).eval(vars);
        } finally {
            CURRENT_FUNCTIONS.set(previous);
        }
    }

    /**
     * FORM P3-T2: strict evaluation, for contexts where a silent default hides a bug (writes,
     * invariants). {@code - * / %}, unary minus and {@code +} with a null operand, a non-numeric operand
     * of {@code - * / %}, and division or remainder by zero throw an {@link ExpressionException} naming the
     * sub-expression, where {@link #evaluate} (lenient) treats null as 0 and {@code x / 0} as 0.
     */
    public static Object evaluateStrict(String expression, Map<String, Object> vars, FunctionRegistry functions) {
        Boolean previous = STRICT.get();
        STRICT.set(Boolean.TRUE);
        try {
            return evaluate(expression, vars, functions);
        } finally {
            STRICT.set(previous);
        }
    }

    /** {@link #evaluateBoolean(String, Map, FunctionRegistry)} under {@link #evaluateStrict} rules. */
    public static boolean evaluateBooleanStrict(String expression, Map<String, Object> vars, FunctionRegistry functions) {
        Boolean previous = STRICT.get();
        STRICT.set(Boolean.TRUE);
        try {
            return evaluateBoolean(expression, vars, functions);
        } finally {
            STRICT.set(previous);
        }
    }

    /**
     * Parse and evaluate as a strict boolean (invariant-style usage). Throws
     * {@link ExpressionException} if the expression doesn't parse or its
     * top-level result isn't a {@link Boolean}.
     */
    public static boolean evaluateBoolean(String expression, Map<String, Object> vars) {
        return evaluateBoolean(expression, vars, FunctionRegistry.empty());
    }

    /** {@link #evaluateBoolean(String, Map)} with a {@link FunctionRegistry} — see {@link #evaluate(String, Map, FunctionRegistry)}. */
    public static boolean evaluateBoolean(String expression, Map<String, Object> vars, FunctionRegistry functions) {
        Object result = evaluate(expression, vars, functions);
        if (!(result instanceof Boolean b)) {
            throw new ExpressionException(
                    "expression did not evaluate to a boolean: " + expression
                            + " (result=" + result + ")");
        }
        return b;
    }

    /**
     * LNCH-15: a named set of callable functions for {@code name(args...)} / {@code
     * receiver.name(args...)} call syntax. Functions receive the raw (unevaluated) argument
     * {@link Node}s rather than pre-evaluated values -- some functions (e.g. a quantifier's
     * lambda argument, or {@code uniqueBy}'s per-item key-field-name argument) need to control
     * how/when their arguments are evaluated rather than have them evaluated eagerly against the
     * outer scope.
     */
    @FunctionalInterface
    public interface FunctionRegistry {
        ExprFunction lookup(String name);

        static FunctionRegistry empty() {
            return name -> null;
        }

        static FunctionRegistry of(Map<String, ExprFunction> functions) {
            Map<String, ExprFunction> copy = Map.copyOf(functions);
            return copy::get;
        }
    }

    @FunctionalInterface
    public interface ExprFunction {
        Object call(List<Node> args, Map<String, Object> vars);
    }

    /** Function names parsed as a single direct call rather than {@code receiver.method(...)} sugar. */
    private static final java.util.Set<String> DIRECT_FUNCTION_NAMES = java.util.Set.of("scope.exists");

    private static final ThreadLocal<FunctionRegistry> CURRENT_FUNCTIONS =
            ThreadLocal.withInitial(FunctionRegistry::empty);

    /**
     * The set of field/variable names (dotted paths kept whole) referenced anywhere in the
     * expression. Used for compile-time "unknown field" validation. Throws
     * {@link ExpressionException} if the expression doesn't parse.
     */
    public static java.util.Set<String> referencedFields(String expression) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        parse(expression).collectFields(out);
        return out;
    }

    /**
     * The set of function names called anywhere in the expression (B2 risk classification) --
     * scope-prefixed names (e.g. {@code "scope.exists"}) kept as declared so a caller can
     * distinguish a cross-record call from an ordinary one. Empty for an expression made only of
     * literals, variables, and arithmetic/comparison/logical operators. Throws
     * {@link ExpressionException} if the expression doesn't parse.
     */
    public static java.util.Set<String> functionCalls(String expression) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        parse(expression).collectFunctionCalls(out);
        return out;
    }

    /**
     * True if the expression's top-level operator always yields a boolean (comparison,
     * {@code &&}/{@code ||}, unary {@code !}, or a boolean literal) — a syntactic check, not an
     * evaluation, so it doesn't require variable bindings. Used for compile-time validation of
     * invariant expressions, which must be boolean-shaped. Throws {@link ExpressionException} if
     * the expression doesn't parse.
     */
    public static boolean isBooleanShaped(String expression) {
        return parse(expression).looksBoolean();
    }

    /**
     * One operand of an ordered comparison: {@code kind} is {@code "field"} (a field reference,
     * {@code field} holds its dotted path), {@code "literal"} ({@code literal} holds the parsed value,
     * possibly null) or {@code "other"} (arithmetic, a call, a nested expression).
     */
    public record Operand(String kind, String field, Object literal) {
    }

    /** REG-269: an ordered comparison ({@code < <= > >=}) found in an expression. */
    public record OrderedComparison(String op, Operand left, Operand right) {
    }

    /** REG-269: every ordered comparison in the expression, in source order. Throws
     *  {@link ExpressionException} if the expression doesn't parse. */
    public static List<OrderedComparison> orderedComparisons(String expression) {
        List<OrderedComparison> out = new ArrayList<>();
        parse(expression).collectOrderedComparisons(out);
        return out;
    }

    private static Operand operandOf(Node node) {
        if (node instanceof Var var) {
            return new Operand("field", var.name(), null);
        }
        if (node instanceof Literal literal) {
            return new Operand("literal", null, literal.value());
        }
        return new Operand("other", null, null);
    }

    // ---- AST ---------------------------------------------------------------

    private record Literal(Object value) implements Node {
        public Object eval(Map<String, Object> vars) {
            return value;
        }

        public String render() {
            return value instanceof String s ? "'" + s + "'" : String.valueOf(value);
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.literal(value);
        }

        public boolean looksBoolean() {
            return value instanceof Boolean;
        }
    }

    private record Var(String name) implements Node {
        public Object eval(Map<String, Object> vars) {
            if (vars == null) {
                return null;
            }
            if (vars.containsKey(name)) {
                return vars.get(name);
            }
            if (name.indexOf('.') < 0) {
                return null;
            }
            // Dotted path not present as a literal key: walk nested maps segment by segment.
            Object current = vars;
            for (String segment : name.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(segment);
            }
            return current;
        }

        public void collectFields(java.util.Set<String> out) {
            out.add(name);
        }

        public String describeFieldPath() {
            return name;
        }

        public String render() {
            return name;
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.variable(name);
        }
    }

    private record Unary(String op, Node operand) implements Node {
        public Object eval(Map<String, Object> vars) {
            Object v = operand.eval(vars);
            if ("!".equals(op)) {
                return !truthy(v);
            }
            requireNumeric(this, operand, v);
            return normalize(dec(v).negate());
        }

        public String render() {
            return op + operand.render();
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.unary(this, op, operand, operand.typeOf(session));
        }

        public boolean looksBoolean() {
            return "!".equals(op);
        }

        public void collectFields(java.util.Set<String> out) {
            operand.collectFields(out);
        }

        public void collectOrderedComparisons(List<OrderedComparison> out) {
            operand.collectOrderedComparisons(out);
        }

        public void collectFunctionCalls(java.util.Set<String> out) {
            operand.collectFunctionCalls(out);
        }
    }

    /** FORM P3-T2: {@code ['A', 'B']}. Evaluates to a fresh {@link java.util.ArrayList}. */
    private record ListLiteral(List<Node> items) implements Node {
        public String render() {
            return "[" + items.stream().map(Node::render).collect(java.util.stream.Collectors.joining(", ")) + "]";
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            items.forEach(item -> item.typeOf(session));
            return ExprType.LIST;
        }

        public Object eval(Map<String, Object> vars) {
            List<Object> out = new ArrayList<>(items.size());
            for (Node item : items) {
                out.add(item.eval(vars));
            }
            return out;
        }

        public void collectFields(java.util.Set<String> out) {
            items.forEach(i -> i.collectFields(out));
        }

        public void collectFunctionCalls(java.util.Set<String> out) {
            items.forEach(i -> i.collectFunctionCalls(out));
        }

        public void collectOrderedComparisons(List<OrderedComparison> out) {
            items.forEach(i -> i.collectOrderedComparisons(out));
        }
    }

    /** FORM P3-T2: {@code cond ? a : b}; only the chosen branch is evaluated. */
    private record Ternary(Node cond, Node whenTrue, Node whenFalse) implements Node {
        public String render() {
            return cond.render() + " ? " + whenTrue.render() + " : " + whenFalse.render();
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.ternary(this, cond.typeOf(session), whenTrue.typeOf(session), whenFalse.typeOf(session));
        }

        public Object eval(Map<String, Object> vars) {
            return truthy(cond.eval(vars)) ? whenTrue.eval(vars) : whenFalse.eval(vars);
        }

        public boolean looksBoolean() {
            return whenTrue.looksBoolean() && whenFalse.looksBoolean();
        }

        public void collectFields(java.util.Set<String> out) {
            cond.collectFields(out);
            whenTrue.collectFields(out);
            whenFalse.collectFields(out);
        }

        public void collectFunctionCalls(java.util.Set<String> out) {
            cond.collectFunctionCalls(out);
            whenTrue.collectFunctionCalls(out);
            whenFalse.collectFunctionCalls(out);
        }

        public void collectOrderedComparisons(List<OrderedComparison> out) {
            cond.collectOrderedComparisons(out);
            whenTrue.collectOrderedComparisons(out);
            whenFalse.collectOrderedComparisons(out);
        }
    }

    /** {@code in}: membership of {@code item} in a list, by the same loose equality as {@code ==}. A non-list right side is false. */
    private static boolean contains(Object collection, Object item) {
        if (!(collection instanceof java.util.Collection<?> c)) {
            return false;
        }
        for (Object element : c) {
            if (equalsLoose(item, element)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> ORDERED_OPS = java.util.Set.of("<", "<=", ">", ">=");

    private static final java.util.Set<String> BOOLEAN_OPS = java.util.Set.of(
            "&&", "||", "==", "!=", "<", "<=", ">", ">=", "in", "not in");

    private record Binary(String op, Node left, Node right) implements Node {
        public String render() {
            return wrap(left) + " " + op + " " + wrap(right);
        }

        private static String wrap(Node child) {
            return child instanceof Binary || child instanceof Ternary ? "(" + child.render() + ")" : child.render();
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.binary(this, op, left, left.typeOf(session), right, right.typeOf(session));
        }

        public boolean looksBoolean() {
            return BOOLEAN_OPS.contains(op);
        }

        public void collectFields(java.util.Set<String> out) {
            left.collectFields(out);
            right.collectFields(out);
        }

        public void collectFunctionCalls(java.util.Set<String> out) {
            left.collectFunctionCalls(out);
            right.collectFunctionCalls(out);
        }

        public void collectOrderedComparisons(List<OrderedComparison> out) {
            if (ORDERED_OPS.contains(op)) {
                out.add(new OrderedComparison(op, operandOf(left), operandOf(right)));
            }
            left.collectOrderedComparisons(out);
            right.collectOrderedComparisons(out);
        }

        public Object eval(Map<String, Object> vars) {
            switch (op) {
                case "&&": return truthy(left.eval(vars)) && truthy(right.eval(vars));
                case "||": return truthy(left.eval(vars)) || truthy(right.eval(vars));
                case "??": {
                    Object first = left.eval(vars);
                    return first != null ? first : right.eval(vars);
                }
                default: break;
            }
            Object l = left.eval(vars);
            Object r = right.eval(vars);
            switch (op) {
                case "+":
                    // Strict: a null side is an error (use `x ?? 0` or `x ?? ''`), never "" or 0.
                    if (STRICT.get() && (l == null || r == null)) {
                        throw strictFailure(this, "null operand `" + (l == null ? left : right).render() + "`");
                    }
                    // Arithmetic when both sides are numeric (incl. numeric strings); else concatenate.
                    if (isNumericLike(l) && isNumericLike(r)) {
                        return normalize(dec(l).add(dec(r)));
                    }
                    return stringify(l) + stringify(r);
                case "-": requireNumeric(this, left, l); requireNumeric(this, right, r);
                    return normalize(dec(l).subtract(dec(r)));
                case "*": requireNumeric(this, left, l); requireNumeric(this, right, r);
                    return normalize(dec(l).multiply(dec(r)));
                case "/": case "%": {
                    requireNumeric(this, left, l);
                    requireNumeric(this, right, r);
                    java.math.BigDecimal divisor = dec(r);
                    if (divisor.signum() == 0) {
                        if (STRICT.get()) {
                            throw strictFailure(this, "division by zero");
                        }
                        return 0L;
                    }
                    return normalize("/".equals(op)
                            ? dec(l).divide(divisor, java.math.MathContext.DECIMAL64)
                            : dec(l).remainder(divisor));
                }
                case "in": return contains(r, l);
                case "not in": return !contains(r, l);
                case "==": return equalsLoose(l, r);
                case "!=": return !equalsLoose(l, r);
                case "<": return ordered(l, r, c -> c < 0);
                case "<=": return ordered(l, r, c -> c <= 0);
                case ">": return ordered(l, r, c -> c > 0);
                case ">=": return ordered(l, r, c -> c >= 0);
                default: throw new ExpressionException("unknown operator: " + op);
            }
        }
    }

    /**
     * LNCH-15: {@code name(args...)}, or {@code receiver.name(args...)} sugar desugared at parse
     * time into {@code name(receiver, args...)} (see {@code Parser#parseCallSuffix}). Looks up
     * {@code name} in the current thread's {@link FunctionRegistry} (set by {@link
     * #evaluate(String, Map, FunctionRegistry)}) at eval time -- functions get the raw argument
     * nodes, not pre-evaluated values, so e.g. a quantifier's lambda argument or {@code
     * uniqueBy}'s per-item key-field-name argument can control their own evaluation.
     */
    private record Call(String name, List<Node> args) implements Node {
        public String render() {
            return name + "(" + args.stream().map(Node::render).collect(java.util.stream.Collectors.joining(", ")) + ")";
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.call(this, name, args);
        }

        public Object eval(Map<String, Object> vars) {
            ExprFunction fn = CURRENT_FUNCTIONS.get().lookup(name);
            if (fn == null) {
                throw new ExpressionException("unknown function: " + name);
            }
            Object result = fn.call(args, vars);
            // FORM P3-T5c: the date functions answer null for an operand that is not a date (a field expression stays
            // soft). Under strict evaluation -- the flow `set` step, which replaced computeValue's
            // COMPUTE_VALUE_INVALID_OPERAND -- that null is an error, never a silently stored empty value.
            if (result == null && STRICT.get() && ("addDays".equals(name) || "daysBetween".equals(name))) {
                throw strictFailure(this, "operand is not a date");
            }
            return result;
        }

        public boolean looksBoolean() {
            // Permissive by design, not just for the known BOOLEAN_FUNCTIONS: a function call at
            // the top level of an invariant expression is always assumed intentional. Before this
            // grammar accepted call syntax at all, ANY such expression failed to parse and the
            // boolean-shape check was silently skipped for it (see SemanticValidator's
            // validateInvariantExpressionShape) -- now that it parses, staying silent for
            // unrecognized function names preserves that same "we don't know, don't flag it"
            // behavior instead of turning it into a false "must evaluate to a boolean" error
            // (confirmed live: broke on a legitimate capability-invocation expression,
            // cap.persistence.unique(...), that isn't one of CelInvariantEngine's named forms).
            return true;
        }

        public void collectFields(java.util.Set<String> out) {
            // uniqueBy's 2nd arg is a per-item key field name, not a reference into the outer
            // scope (mirrors Lambda's own skip, below, for the same reason).
            int skipFrom = "uniqueBy".equals(name) ? 1 : args.size();
            for (int i = 0; i < args.size(); i++) {
                if (i < skipFrom || i > skipFrom) {
                    args.get(i).collectFields(out);
                }
            }
        }

        public void collectFunctionCalls(java.util.Set<String> out) {
            out.add(name);
            for (Node arg : args) {
                arg.collectFunctionCalls(out);
            }
        }

        public void collectOrderedComparisons(List<OrderedComparison> out) {
            for (Node arg : args) {
                arg.collectOrderedComparisons(out);
            }
        }
    }

    /**
     * LNCH-15: {@code param => body}, valid only as a function-call argument (e.g. {@code
     * items.all(x => x.field > 0)}) -- {@link #eval} always throws; a consuming function invokes
     * {@link #invoke} once per item instead, binding {@code param} to that item in a scope
     * layered over the outer variables.
     */
    private record Lambda(String param, Node body) implements Node {
        public String render() {
            return param + " => " + body.render();
        }

        public ExprType typeOf(ExpressionTypeChecker.Session session) {
            return session.lambda(param, body);
        }

        public boolean isLambda() {
            return true;
        }

        public Object eval(Map<String, Object> vars) {
            throw new ExpressionException("lambda expression used outside a function-call argument");
        }

        public void collectFields(java.util.Set<String> out) {
            // The body's field refs (e.g. "x.field") are scoped to the per-item binding, not the
            // outer concept's fields -- collecting them would produce false "unknown field" hits.
        }

        public void collectFunctionCalls(java.util.Set<String> out) {
            // Unlike collectFields, a call inside the lambda body IS a real function call worth
            // surfacing (e.g. items.all(x => x.tag.matches("A.*"))) -- the per-item scoping that
            // makes field refs meaningless outside their binding doesn't apply to call names.
            body.collectFunctionCalls(out);
        }

        public Object invokeLambda(Object item, Map<String, Object> outerVars) {
            // A delegating view, not a copy: some outer scopes (e.g. CelInvariantEngine's
            // FieldPathScope) resolve lookups lazily and report an empty entrySet(), so copy-
            // constructing a plain Map from them (new LinkedHashMap<>(outerVars)) would silently
            // produce an empty map instead of a real snapshot.
            Map<String, Object> scope = new java.util.AbstractMap<>() {
                @Override
                public Object get(Object key) {
                    if (!(key instanceof String name)) {
                        return null;
                    }
                    if (name.equals(param)) {
                        return item;
                    }
                    if (name.startsWith(param + ".")) {
                        return resolveNested(item, name.substring(param.length() + 1));
                    }
                    return outerVars == null ? null : outerVars.get(name);
                }

                @Override
                public boolean containsKey(Object key) {
                    return true;
                }

                @Override
                public java.util.Set<Entry<String, Object>> entrySet() {
                    return java.util.Set.of();
                }
            };
            return body.eval(scope);
        }

        private static Object resolveNested(Object current, String path) {
            for (String segment : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(segment);
            }
            return current;
        }
    }

    // ---- Coercion ----------------------------------------------------------

    static boolean isNumericLike(Object v) {
        if (v instanceof Number) {
            return true;
        }
        if (v instanceof String s) {
            try {
                Double.parseDouble(s.trim());
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * REG-269: the relational operators ({@code < <= > >=}). Two numeric(-looking) operands compare as
     * numbers, as before. Two temporal operands -- a {@code LocalDate}/{@code LocalDateTime}/
     * {@code OffsetDateTime}/{@code Instant}/{@code java.util.Date}, or an ISO-8601 date or date-time
     * string as a JSON payload carries one -- compare chronologically. Before this, a date was passed
     * through {@code toNumber} (a non-number is 0), so {@code endDate >= startDate} compared 0 with 0 and
     * was always true: an invariant that looked enforced never fired. A temporal operand against anything
     * that is not temporal (null, a number, free text) cannot be ordered and is {@code false}, never
     * silently true. Everything else keeps the lenient legacy numeric coercion.
     */
    private static boolean ordered(Object l, Object r, java.util.function.IntPredicate test) {
        if (isNumericLike(l) && isNumericLike(r)) {
            return test.test(dec(l).compareTo(dec(r)));
        }
        java.time.Instant li = toInstant(l);
        java.time.Instant ri = toInstant(r);
        if (li != null && ri != null) {
            // REG-318(3): a date against a date-time is a calendar-day comparison, so
            // `visitAt <= windowEnd` holds for a visit at 14:00 on windowEnd itself (a date is 00:00 UTC).
            if (isDateOnly(l) != isDateOnly(r)) {
                return test.test(utcDay(li).compareTo(utcDay(ri)));
            }
            return test.test(li.compareTo(ri));
        }
        if (li != null || ri != null) {
            return false;
        }
        return test.test(dec(l).compareTo(dec(r)));
    }

    private static java.time.LocalDate utcDay(java.time.Instant instant) {
        return instant.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /** @return true for a calendar date with no time of day ({@code LocalDate}, {@code java.sql.Date}, {@code "2026-03-10"}). */
    private static boolean isDateOnly(Object v) {
        if (v instanceof java.time.LocalDate || v instanceof java.sql.Date) {
            return true;
        }
        return v instanceof String s && s.trim().length() == 10 && ISO_DATE_PREFIX.matcher(s.trim()).matches();
    }

    private static final java.util.regex.Pattern ISO_DATE_PREFIX =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([T ].*)?$");

    /** @return the instant a temporal value denotes (a date at 00:00 UTC, a local date-time as UTC), or null. */
    static java.time.Instant toInstant(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.time.Instant i) {
            return i;
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        if (v instanceof java.time.ZonedDateTime z) {
            return z.toInstant();
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        if (v instanceof java.time.LocalDate d) {
            return d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
        if (v instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (!ISO_DATE_PREFIX.matcher(t).matches()) {
                return null;
            }
            try {
                if (t.length() == 10) {
                    return java.time.LocalDate.parse(t).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                }
                String iso = t.replace(' ', 'T');
                try {
                    return java.time.OffsetDateTime.parse(iso).toInstant();
                } catch (java.time.format.DateTimeParseException notOffset) {
                    return java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC);
                }
            } catch (java.time.format.DateTimeParseException notTemporal) {
                return null;
            }
        }
        return null;
    }

    /**
     * FORM P3-T2: a decimal result as the narrowest honest type -- a whole number that fits is a
     * {@code Long} (so totals render without a trailing zero), anything else a {@link java.math.BigDecimal}
     * with trailing zeros stripped. Replaces the old double result, so {@code 0.1 + 0.2} is exactly 0.3.
     */
    static Object normalize(java.math.BigDecimal b) {
        if (b.signum() == 0) {
            return 0L;
        }
        java.math.BigDecimal s = b.stripTrailingZeros();
        if (s.scale() <= 0) {
            try {
                return s.longValueExact();
            } catch (ArithmeticException tooBig) {
                return s;
            }
        }
        return s;
    }

    /**
     * Lenient numeric coercion to a decimal: a number as itself (a double through its shortest decimal
     * text, never its binary expansion), a boolean as 1/0, text through BigDecimal then the legacy
     * {@code Double.parseDouble} grammar ({@code "12d"}), everything else -- and a non-finite double -- 0.
     */
    static java.math.BigDecimal dec(Object v) {
        if (v instanceof java.math.BigDecimal b) {
            return b;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return java.math.BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof java.math.BigInteger bi) {
            return new java.math.BigDecimal(bi);
        }
        if (v instanceof Float f) {
            return f.isNaN() || f.isInfinite() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(f.toString());
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(d);
        }
        if (v instanceof Boolean b) {
            return b ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO;
        }
        if (v instanceof String s) {
            String t = s.trim();
            try {
                return new java.math.BigDecimal(t);
            } catch (NumberFormatException notDecimal) {
                try {
                    double d = Double.parseDouble(t);
                    return Double.isNaN(d) || Double.isInfinite(d) ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(d);
                } catch (NumberFormatException notNumber) {
                    return java.math.BigDecimal.ZERO;
                }
            }
        }
        return java.math.BigDecimal.ZERO;
    }

    /** FORM P3-T2: true while {@link #evaluateStrict} runs on this thread. */
    private static final ThreadLocal<Boolean> STRICT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static ExpressionException strictFailure(Node at, String what) {
        return new ExpressionException(what + " in `" + at.render() + "`");
    }

    /** Strict operand check for {@code - * / %} and unary minus: a null or non-numeric operand is an error, never 0. */
    private static void requireNumeric(Node at, Node operandNode, Object value) {
        if (!STRICT.get()) {
            return;
        }
        if (value == null) {
            throw strictFailure(at, "null operand `" + operandNode.render() + "`");
        }
        if (value instanceof Boolean || !isNumericLike(value)) {
            throw strictFailure(at, "operand `" + operandNode.render() + "` is not a number (" + value + ")");
        }
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return v != null && !v.toString().isBlank();
    }

    private static boolean equalsLoose(Object l, Object r) {
        if (l == null || r == null) {
            return l == null && r == null;
        }
        // R5: mirrors the "+" operator's own numeric-vs-concat decision (isNumericLike, not a bare
        // instanceof Number check) and the <, <=, >, >= operators, which already always compare
        // numerically. Before this, two numeric-LOOKING operands that were both String (as a
        // decimal field arrives when submitted as a JSON string -- deliberate, to avoid a JS float
        // round-trip -- and read back out of an untyped request Map rather than a typed BigDecimal
        // field) fell through to exact string equality: "12.30" != "12.3000" even though they are
        // the same value at different scales. Found live: a groupBy/aggregate proof app's invariant
        // rejected two decimal fields holding numerically-equal, differently-scaled values.
        if (isNumericLike(l) && isNumericLike(r)) {
            return dec(l).compareTo(dec(r)) == 0;
        }
        return stringify(l).equals(stringify(r));
    }

    private static String stringify(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    // ---- Tokenizer ---------------------------------------------------------

    /**
     * FORM P4-T2 (extension ladder, rung 2): a named pure function. {@code body} is an ordinary expression over
     * {@code params}, {@code $} references (such as {@code $prop.cap}) and other functions; it never reads a row field.
     */
    public record UserFunction(String name, List<String> params, String body) {
    }

    /**
     * Expand every call to a user function in {@code expression} by substituting the (already expanded) arguments into
     * the function body, and return the result as expression text. The expanded text is a plain expression, so the
     * Java evaluator, the CEL matcher, the client evaluator and SQL pushdown all receive something they already
     * understand -- functions cost no runtime. Throws {@link ExpressionException} on a wrong argument count, a
     * recursive call chain ({@code a -> b -> a}) or a record-style use of a parameter bound to a non-field argument.
     * An expression that calls no user function comes back unchanged (same text).
     */
    public static String inlineFunctions(String expression, java.util.function.Function<String, UserFunction> lookup) {
        Node before = parse(expression);
        Node after = inlineNode(before, lookup, new ArrayList<>(), Map.of());
        return after == before ? expression : text(after);
    }

    /** Like {@link #inlineFunctions} for a function's own body, with the function already on the call stack. */
    public static String inlineFunctionBody(UserFunction function, java.util.function.Function<String, UserFunction> lookup) {
        List<String> stack = new ArrayList<>();
        stack.add(function.name());
        return text(inlineNode(parse(function.body()), lookup, stack, Map.of()));
    }

    private static Node inlineNode(Node node, java.util.function.Function<String, UserFunction> lookup,
                                   List<String> stack, Map<String, Node> subst) {
        if (node instanceof Var v) {
            return substituteVar(v, subst);
        }
        if (node instanceof Unary u) {
            Node operand = inlineNode(u.operand(), lookup, stack, subst);
            return operand == u.operand() ? u : new Unary(u.op(), operand);
        }
        if (node instanceof Binary b) {
            Node l = inlineNode(b.left(), lookup, stack, subst);
            Node r = inlineNode(b.right(), lookup, stack, subst);
            return l == b.left() && r == b.right() ? b : new Binary(b.op(), l, r);
        }
        if (node instanceof Ternary t) {
            Node c = inlineNode(t.cond(), lookup, stack, subst);
            Node a = inlineNode(t.whenTrue(), lookup, stack, subst);
            Node o = inlineNode(t.whenFalse(), lookup, stack, subst);
            return c == t.cond() && a == t.whenTrue() && o == t.whenFalse() ? t : new Ternary(c, a, o);
        }
        if (node instanceof ListLiteral list) {
            List<Node> items = new ArrayList<>();
            boolean changed = false;
            for (Node item : list.items()) {
                Node mapped = inlineNode(item, lookup, stack, subst);
                changed |= mapped != item;
                items.add(mapped);
            }
            return changed ? new ListLiteral(items) : list;
        }
        if (node instanceof Lambda lambda) {
            Map<String, Node> inner = new java.util.HashMap<>(subst);
            inner.remove(lambda.param());
            Node body = inlineNode(lambda.body(), lookup, stack, inner);
            return body == lambda.body() ? lambda : new Lambda(lambda.param(), body);
        }
        if (node instanceof Call call) {
            List<Node> args = new ArrayList<>();
            boolean changed = false;
            for (Node arg : call.args()) {
                Node mapped = inlineNode(arg, lookup, stack, subst);
                changed |= mapped != arg;
                args.add(mapped);
            }
            UserFunction function = lookup.apply(call.name());
            if (function == null) {
                return changed ? new Call(call.name(), args) : call;
            }
            if (args.size() != function.params().size()) {
                throw new ExpressionException("function " + function.name() + " takes " + function.params().size()
                        + " argument(s) (" + String.join(", ", function.params()) + ") but was called with " + args.size());
            }
            if (stack.contains(function.name())) {
                throw new ExpressionException("function " + function.name() + " calls itself: "
                        + String.join(" -> ", stack) + " -> " + function.name());
            }
            Map<String, Node> bind = new java.util.LinkedHashMap<>();
            for (int i = 0; i < args.size(); i++) {
                bind.put(function.params().get(i), args.get(i));
            }
            stack.add(function.name());
            try {
                return inlineNode(parse(function.body()), lookup, stack, bind);
            } finally {
                stack.remove(stack.size() - 1);
            }
        }
        return node;
    }

    private static Node substituteVar(Var v, Map<String, Node> subst) {
        if (subst.isEmpty()) {
            return v;
        }
        Node direct = subst.get(v.name());
        if (direct != null) {
            return direct;
        }
        int dot = v.name().indexOf('.');
        if (dot > 0) {
            Node base = subst.get(v.name().substring(0, dot));
            if (base != null) {
                if (base instanceof Var field) {
                    return new Var(field.name() + v.name().substring(dot));
                }
                throw new ExpressionException("parameter '" + v.name().substring(0, dot) + "' is read as a record ('"
                        + v.name() + "') but the argument is not a field reference");
            }
        }
        return v;
    }

    /** A faithful, fully parenthesised rendering of a parsed tree: it parses back to the same tree. */
    private static String text(Node node) {
        if (node instanceof Literal literal) {
            Object v = literal.value();
            if (v == null) {
                return "null";
            }
            if (v instanceof String str) {
                return str.indexOf('\'') >= 0 ? "\"" + str + "\"" : "'" + str + "'";
            }
            if (v instanceof Number num) {
                return new java.math.BigDecimal(num.toString()).toPlainString();
            }
            return String.valueOf(v);
        }
        if (node instanceof Var v) {
            return v.name();
        }
        if (node instanceof Unary u) {
            return u.op() + atom(u.operand());
        }
        if (node instanceof Binary b) {
            return atom(b.left()) + " " + b.op() + " " + atom(b.right());
        }
        if (node instanceof Ternary t) {
            return atom(t.cond()) + " ? " + atom(t.whenTrue()) + " : " + atom(t.whenFalse());
        }
        if (node instanceof ListLiteral list) {
            return "[" + list.items().stream().map(ComputedExpression::text).collect(java.util.stream.Collectors.joining(", ")) + "]";
        }
        if (node instanceof Lambda lambda) {
            return lambda.param() + " => " + text(lambda.body());
        }
        if (node instanceof Call call) {
            return call.name() + "(" + call.args().stream().map(ComputedExpression::text).collect(java.util.stream.Collectors.joining(", ")) + ")";
        }
        return node.render();
    }

    private static String atom(Node node) {
        boolean simple = node instanceof Var || node instanceof Call || node instanceof ListLiteral
                || (node instanceof Literal literal && !(literal.value() instanceof Number n && n.doubleValue() < 0));
        return simple ? text(node) : "(" + text(node) + ")";
    }

    private record Token(String type, String text) {
    }

    private static List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        String s = expr == null ? "" : expr;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token("NUM", s.substring(start, i)));
                continue;
            }
            if (c == '\'' || c == '"') {
                int start = ++i;
                while (i < n && s.charAt(i) != c) {
                    i++;
                }
                if (i >= n) {
                    throw new ExpressionException("unterminated string literal");
                }
                tokens.add(new Token("STR", s.substring(start, i)));
                i++;
                continue;
            }
            if (c == '$' || Character.isLetter(c) || c == '_') {
                // LNCH-13: a leading '$' is a pseudo-variable sigil (e.g. $user.id, the current
                // actor context in a row-level access rule) -- only valid as the very first
                // character, not the rest of the identifier or a later dotted segment.
                int start = i;
                if (c == '$') {
                    i++;
                }
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                // FORM P4-T2: a pack function is namespaced `pack::name` -- one identifier, never a dotted path.
                while (c != '$' && i + 2 < n && s.charAt(i) == ':' && s.charAt(i + 1) == ':'
                        && (Character.isLetter(s.charAt(i + 2)) || s.charAt(i + 2) == '_')) {
                    i += 2;
                    while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                        i++;
                    }
                }
                // Dotted field path: consume ".segment" chains (cliente.tipo), but not a
                // trailing '.' with nothing after it (leave that as a syntax error downstream).
                while (i < n && s.charAt(i) == '.' && i + 1 < n
                        && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '_')) {
                    i++;
                    while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                        i++;
                    }
                }
                tokens.add(new Token("IDENT", s.substring(start, i)));
                continue;
            }
            // multi-char operators first
            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||") || two.equals("=>") || two.equals("??")) {
                tokens.add(new Token("OP", two));
                i += 2;
                continue;
            }
            if (c == ',') {
                tokens.add(new Token("COMMA", ","));
                i++;
                continue;
            }
            if ("+-*/%<>!()?:[]".indexOf(c) >= 0) {
                tokens.add(new Token(c == '(' ? "LP" : c == ')' ? "RP" : c == '[' ? "LB" : c == ']' ? "RB" : "OP",
                        String.valueOf(c)));
                i++;
                continue;
            }
            throw new ExpressionException("unexpected character '" + c + "' in expression");
        }
        tokens.add(new Token("EOF", ""));
        return tokens;
    }

    // ---- Parser (recursive descent) ----------------------------------------

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Node parseAll() {
            Node node = parseTernary();
            expect("EOF");
            return node;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean matchOp(String op) {
            if (peek().type().equals("OP") && peek().text().equals(op)) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(String type) {
            if (!peek().type().equals(type)) {
                throw new ExpressionException("expected " + type + " but found '" + peek().text() + "'");
            }
            pos++;
        }

        /** expr := coalesce ( '?' expr ':' expr )? -- the lowest precedence. */
        private Node parseTernary() {
            Node cond = parseCoalesce();
            if (matchOp("?")) {
                Node whenTrue = parseTernary();
                if (!matchOp(":")) {
                    throw new ExpressionException("expected ':' in a ?: expression but found '" + peek().text() + "'");
                }
                return new Ternary(cond, whenTrue, parseTernary());
            }
            return cond;
        }

        private Node parseCoalesce() {
            Node left = parseOr();
            while (matchOp("??")) {
                left = new Binary("??", left, parseOr());
            }
            return left;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (matchOp("||")) {
                left = new Binary("||", left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseEquality();
            while (matchOp("&&")) {
                left = new Binary("&&", left, parseEquality());
            }
            return left;
        }

        private Node parseEquality() {
            Node left = parseRelational();
            while (peek().type().equals("OP") && (peek().text().equals("==") || peek().text().equals("!="))) {
                String op = next().text();
                left = new Binary(op, left, parseRelational());
            }
            return left;
        }

        private Node parseRelational() {
            Node left = parseAdditive();
            while (peek().type().equals("OP")
                    && (peek().text().equals("<") || peek().text().equals("<=")
                    || peek().text().equals(">") || peek().text().equals(">="))) {
                String op = next().text();
                left = new Binary(op, left, parseAdditive());
            }
            // 'in' / 'not in' are infix only; a field called `in` still works anywhere else.
            if (peek().type().equals("IDENT") && peek().text().equals("in")) {
                next();
                return new Binary("in", left, parseAdditive());
            }
            if (peek().type().equals("IDENT") && peek().text().equals("not")
                    && tokens.get(pos + 1).type().equals("IDENT") && tokens.get(pos + 1).text().equals("in")) {
                pos += 2;
                return new Binary("not in", left, parseAdditive());
            }
            return left;
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (peek().type().equals("OP") && (peek().text().equals("+") || peek().text().equals("-"))) {
                String op = next().text();
                left = new Binary(op, left, parseMultiplicative());
            }
            return left;
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (peek().type().equals("OP")
                    && (peek().text().equals("*") || peek().text().equals("/") || peek().text().equals("%"))) {
                String op = next().text();
                left = new Binary(op, left, parseUnary());
            }
            return left;
        }

        private Node parseUnary() {
            if (peek().type().equals("OP") && (peek().text().equals("-") || peek().text().equals("!"))) {
                String op = next().text();
                return new Unary(op, parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            Token t = peek();
            switch (t.type()) {
                case "NUM":
                    next();
                    try {
                        return new Literal(normalize(new java.math.BigDecimal(t.text())));
                    } catch (NumberFormatException e) {
                        throw new ExpressionException("invalid number: " + t.text());
                    }
                case "STR":
                    next();
                    return new Literal(t.text());
                case "IDENT":
                    next();
                    if (peek().type().equals("LP")) {
                        return parseCallSuffix(t.text());
                    }
                    switch (t.text()) {
                        case "true": return new Literal(Boolean.TRUE);
                        case "false": return new Literal(Boolean.FALSE);
                        case "null": return new Literal(null);
                        default: return new Var(t.text());
                    }
                case "LP":
                    next();
                    Node inner = parseTernary();
                    expect("RP");
                    return inner;
                case "LB": {
                    next();
                    List<Node> items = new ArrayList<>();
                    if (!peek().type().equals("RB")) {
                        items.add(parseTernary());
                        while (peek().type().equals("COMMA")) {
                            next();
                            items.add(parseTernary());
                        }
                    }
                    expect("RB");
                    return new ListLiteral(items);
                }
                default:
                    throw new ExpressionException("unexpected token '" + t.text() + "' in expression");
            }
        }

        /**
         * LNCH-15: {@code identText} was followed by {@code (} -- a function call. A dotted
         * ident (tokenized as one IDENT, e.g. {@code "allergies.uniqueBy"}) is receiver.method
         * sugar desugared into {@code method(receiver, args...)}, UNLESS it's one of the fixed
         * {@link #DIRECT_FUNCTION_NAMES} (namespace-style names like {@code scope.exists} that
         * are a single function, not a call on a real {@code scope} field).
         */
        private Node parseCallSuffix(String identText) {
            next(); // consume LP
            List<Node> args = parseArgList();
            expect("RP");
            if (DIRECT_FUNCTION_NAMES.contains(identText)) {
                return new Call(identText, args);
            }
            int lastDot = identText.lastIndexOf('.');
            if (lastDot < 0) {
                return new Call(identText, args);
            }
            String receiverPath = identText.substring(0, lastDot);
            String methodName = identText.substring(lastDot + 1);
            List<Node> combinedArgs = new ArrayList<>(args.size() + 1);
            combinedArgs.add(new Var(receiverPath));
            combinedArgs.addAll(args);
            return new Call(methodName, combinedArgs);
        }

        private List<Node> parseArgList() {
            List<Node> args = new ArrayList<>();
            if (peek().type().equals("RP")) {
                return args;
            }
            args.add(parseArg());
            while (peek().type().equals("COMMA")) {
                next();
                args.add(parseArg());
            }
            return args;
        }

        /** A single function-call argument: either {@code param => body} (a lambda) or an ordinary expression. */
        private Node parseArg() {
            if (peek().type().equals("IDENT")
                    && tokens.get(pos + 1).type().equals("OP")
                    && tokens.get(pos + 1).text().equals("=>")) {
                String param = next().text();
                next(); // consume =>
                Node body = parseTernary();
                return new Lambda(param, body);
            }
            return parseTernary();
        }
    }
}
