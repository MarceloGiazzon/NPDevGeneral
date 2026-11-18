package com.npdev.dsl.v1.query;

import com.npdev.dsl.v1.expr.ComputedExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The predicate grammar of a query {@code where} and a row {@code access.read} rule, FORM P3-T4: not a
 * grammar of its own any more. The text is an expression of the one expression language
 * ({@link ComputedExpression}); this class LOWERS the shared AST to the clause records the SQL and
 * in-memory filter builders consume, and refuses, naming it, anything that cannot be pushed down.
 * Usable from both sides of the dependency edge: the kernel depends on this module, the DSL validator
 * cannot depend on the kernel.
 *
 * <p>A bind parameter is written {@code $params.name} (it was {@code :name}); the caller that has the
 * bound values (the kernel) resolves a {@link PredicateLiteral.Placeholder}, a caller that only has the
 * model (the validator) checks the name against the query's declared {@code parameters[]}.
 */
public final class QueryPredicateGrammar {

    private QueryPredicateGrammar() {
    }

    public enum Operator {
        EQ("=="), NEQ("!="), GTE(">="), LTE("<="), GT(">"), LT("<");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /** Operators longest-first, so {@code >=} is never mis-read as {@code >}. */
    private static final Operator[] OPERATORS_LONGEST_FIRST = {
            Operator.EQ, Operator.NEQ, Operator.GTE, Operator.LTE, Operator.GT, Operator.LT,
    };

    public sealed interface Literal {
        record Value(Object value) implements Literal {
        }

        /** An unresolved {@code :name} bind placeholder -- resolution is the CALLER's job. */
        record Placeholder(String name) implements Literal {
        }
    }

    public record Clause(String field, Operator operator, Literal literal) {
    }

    /** Thrown when a {@code where} cannot be parsed. Never caught-and-ignored by design. */
    public static final class UnsupportedPredicateException extends RuntimeException {
        private final String where;
        private final String clause;

        public UnsupportedPredicateException(String where, String clause, String reason) {
            super("cannot compile query predicate " + quote(where)
                    + (clause == null ? "" : " at clause " + quote(clause)) + " -- " + reason
                    + ". Supported: conditions combined with && and || (and ! and parentheses), each one of "
                    + "field (== | != | > | >= | < | <=) literal, field in ['a', 'b'], field == null, "
                    + "contains(field, 'x'), startsWith(field, 'x'), or a bare boolean field; "
                    + "field may be a reference path (customerId.name); the other side being 'text', a number, "
                    + "true, false, $params.name (a bind parameter) or $user.id. A predicate that cannot be "
                    + "compiled is refused rather than applied partially: an unenforced filter "
                    + "returns rows the author asked to exclude, with no error anywhere.");
            this.where = where;
            this.clause = clause;
        }

        public String where() {
            return where;
        }

        public String clause() {
            return clause;
        }

        private static String quote(String value) {
            return value == null ? "<null>" : "\"" + value + "\"";
        }
    }

    /**
     * v1 view of {@link #parseGroups}: the AND-combined, plain-field comparisons the first grammar
     * carried. FORM P3-T4: no parser of its own any more -- it lowers through {@link #parseGroups} and
     * refuses anything that is not one AND group of {@code field op literal} clauses.
     *
     * @return the AND-combined clauses for {@code where}; an empty list when {@code where} is
     *         null/blank (no predicate declared is not the same as a predicate that failed).
     * @throws UnsupportedPredicateException when any part of {@code where} is outside the grammar
     */
    public static List<Clause> parse(String where) {
        List<List<PredicateClause>> groups = parseGroups(where);
        if (groups.isEmpty()) {
            return List.of();
        }
        if (groups.size() > 1) {
            throw new UnsupportedPredicateException(where, null,
                    "'||' (OR) is not supported here -- ConceptQuery filters are AND-combined by contract, "
                            + "and silently treating an OR as an AND would return the wrong rows");
        }
        List<Clause> clauses = new ArrayList<>();
        for (PredicateClause clause : groups.get(0)) {
            if (!(clause.path() instanceof GroupByJoinGrammar.Target.Direct direct)) {
                throw new UnsupportedPredicateException(where, null,
                        "a reference path is not supported here -- only a plain field name on the left of a comparison");
            }
            Operator operator = switch (clause.operator()) {
                case EQ -> Operator.EQ;
                case NEQ -> Operator.NEQ;
                case GTE -> Operator.GTE;
                case LTE -> Operator.LTE;
                case GT -> Operator.GT;
                case LT -> Operator.LT;
                default -> throw new UnsupportedPredicateException(where, null,
                        "'" + clause.operator().token() + "' is not supported here -- only == != > >= < <=");
            };
            Literal literal;
            if (clause.literal() instanceof PredicateLiteral.Value value) {
                literal = new Literal.Value(value.value());
            } else if (clause.literal() instanceof PredicateLiteral.Placeholder placeholder) {
                literal = new Literal.Placeholder(placeholder.name());
            } else {
                throw new UnsupportedPredicateException(where, null,
                        "only a literal or a $params.name placeholder is supported on the right of a comparison here");
            }
            clauses.add(new Clause(direct.field(), operator, literal));
        }
        return List.copyOf(clauses);
    }

    /** Upper bound on the OR-groups a {@code where} may expand to (De Morgan / distribution of {@code &&} over {@code ||}). */
    public static final int MAX_GROUPS = 64;

    /**
     * FORM P3-T4: a query {@code where} is an expression of the one expression language
     * ({@link ComputedExpression}); this method no longer parses anything. It lowers the shared AST to
     * disjunctive normal form -- an OR of AND-combined {@link PredicateClause}s -- the shape the SQL and
     * in-memory filter builders already consume.
     *
     * <p>Pushable: {@code && || !} and parentheses, {@code == != < <= > >=} between a field (or a
     * reference path, {@code customerId.name}, bounded by {@link GroupByJoinGrammar#MAX_JOIN_HOPS}) and a
     * literal, {@code $params.name} or {@code $user.id|actorId|tenantId}; {@code field == null} /
     * {@code != null}; {@code field in ['a','b']} and {@code not in}; {@code contains(field,'x')} and
     * {@code startsWith(field,'x')}; a bare boolean field. Anything else (arithmetic, {@code ?:},
     * {@code ??}, any other function) is refused, naming it: a predicate that cannot be pushed down is
     * never applied partially.
     *
     * @return the OR-of-AND clause groups for {@code where}; an empty list when {@code where} is
     *         null/blank
     * @throws UnsupportedPredicateException when any part of {@code where} is outside this grammar
     */
    public static List<List<PredicateClause>> parseGroups(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        Map<String, Object> tree;
        try {
            tree = ComputedExpression.toPortableTree(where.trim());
        } catch (ComputedExpression.ExpressionException syntax) {
            throw new UnsupportedPredicateException(where, null, syntaxReason(where, syntax));
        }
        List<List<PredicateClause>> groups = lower(where, tree, false);
        if (groups.isEmpty()) {
            throw new UnsupportedPredicateException(where, null, "no clause found");
        }
        List<List<PredicateClause>> frozen = new ArrayList<>();
        for (List<PredicateClause> group : groups) {
            frozen.add(List.copyOf(group));
        }
        return List.copyOf(frozen);
    }

    /**
     * The field paths a {@code where} names, one per occurrence, in TEXTUAL order -- the numbering the
     * reference index and {@code npdev migrate rename} share ({@code #N} is "the Nth field written"), which
     * lowering to OR-groups can reorder or multiply ({@code x not in [a, b]} is one field, two clauses).
     * $-names ({@code $params.x}, {@code $user.id}) are not fields and are skipped.
     *
     * @throws UnsupportedPredicateException when {@code where} is not a pushable predicate
     */
    public static List<GroupByJoinGrammar.Target> referencedPaths(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        parseGroups(where);
        List<GroupByJoinGrammar.Target> out = new ArrayList<>();
        collectPaths(where, ComputedExpression.toPortableTree(where.trim()), out);
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static void collectPaths(String where, Map<String, Object> node, List<GroupByJoinGrammar.Target> out) {
        switch (String.valueOf(node.get("k"))) {
            case "var": {
                String name = String.valueOf(node.get("name"));
                if (!name.startsWith("$")) {
                    out.add(pathTarget(where, name));
                }
                return;
            }
            case "un":
                collectPaths(where, child(node, "a"), out);
                return;
            case "bin":
                collectPaths(where, child(node, "l"), out);
                collectPaths(where, child(node, "r"), out);
                return;
            case "call":
                for (Map<String, Object> arg : args(node)) {
                    collectPaths(where, arg, out);
                }
                return;
            case "list":
                for (Object item : (List<?>) node.get("items")) {
                    collectPaths(where, (Map<String, Object>) item, out);
                }
                return;
            default:
                return;
        }
    }

    private static String syntaxReason(String where, ComputedExpression.ExpressionException syntax) {
        boolean bindColon = false;
        boolean inQuote = false;
        for (int i = 0; i + 1 < where.length(); i++) {
            char c = where.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == ':' && (Character.isLetter(where.charAt(i + 1)) || where.charAt(i + 1) == '_')) {
                bindColon = true;
            }
        }
        return syntax.getMessage() + (bindColon
                ? " -- a query bind placeholder is written $params.name (the ':name' spelling is gone)"
                : "");
    }

    // ---- lowering ---------------------------------------------------------------------------------

    private static List<List<PredicateClause>> lower(String where, Map<String, Object> node, boolean negated) {
        String kind = String.valueOf(node.get("k"));
        switch (kind) {
            case "un": {
                if ("!".equals(node.get("op"))) {
                    return lower(where, child(node, "a"), !negated);
                }
                throw refuse(where, "a unary '" + node.get("op") + "' is not a condition");
            }
            case "bin": {
                String op = String.valueOf(node.get("op"));
                switch (op) {
                    case "&&":
                        return negated
                                ? union(where, lower(where, child(node, "l"), true), lower(where, child(node, "r"), true))
                                : cross(where, lower(where, child(node, "l"), false), lower(where, child(node, "r"), false));
                    case "||":
                        return negated
                                ? cross(where, lower(where, child(node, "l"), true), lower(where, child(node, "r"), true))
                                : union(where, lower(where, child(node, "l"), false), lower(where, child(node, "r"), false));
                    case "==": case "!=": case "<": case "<=": case ">": case ">=":
                        return List.of(List.of(comparison(where, op, child(node, "l"), child(node, "r"), negated)));
                    case "in": case "not in":
                        return membership(where, "not in".equals(op) != negated, child(node, "l"), child(node, "r"));
                    default:
                        throw refuse(where, "'" + op + "' cannot be pushed down into a query where -- only && || ! "
                                + "== != < <= > >= in, contains(...) and startsWith(...) are lowered to a filter");
                }
            }
            case "call": {
                String name = String.valueOf(node.get("name"));
                if (("contains".equals(name) || "startsWith".equals(name)) && args(node).size() == 2) {
                    if (negated) {
                        throw refuse(where, "a negated " + name + "(...) cannot be pushed down; "
                                + "state the condition positively");
                    }
                    Operand field = operand(where, args(node).get(0));
                    Operand value = operand(where, args(node).get(1));
                    if (!(field instanceof Operand.Field fieldOperand) || !(value instanceof Operand.Lit literal)) {
                        throw refuse(where, name + "(field, literal): the first argument must be a field and the second a literal");
                    }
                    return List.of(List.of(new PredicateClause(fieldOperand.target(),
                            "contains".equals(name) ? PredicateOperator.CONTAINS : PredicateOperator.STARTS_WITH,
                            literal.literal())));
                }
                if ("withinKm".equals(name)) {
                    if (args(node).size() != 5) {
                        throw refuse(where, "withinKm takes 5 arguments: (lat1, lng1, lat2, lng2, km)");
                    }
                    return List.of(List.of(distanceClause(where, args(node).subList(0, 4), args(node).get(4),
                            negated ? PredicateLiteral.DistanceCmp.GT : PredicateLiteral.DistanceCmp.LTE)));
                }
                throw refuse(where, "function '" + name + "' cannot be pushed down into a query where"
                        + " (pushable: contains, startsWith, withinKm, distanceKm compared with < <= > >=)");
            }
            case "var": {
                Operand operand = operand(where, node);
                if (operand instanceof Operand.Field field) {
                    return List.of(List.of(new PredicateClause(field.target(), PredicateOperator.EQ,
                            new PredicateLiteral.Value(!negated))));
                }
                throw refuse(where, "'" + node.get("name") + "' is not a field and cannot stand alone as a condition");
            }
            default:
                throw refuse(where, "a " + kindName(kind) + " cannot be pushed down into a query where");
        }
    }

    private static PredicateClause comparison(
            String where, String op, Map<String, Object> leftNode, Map<String, Object> rightNode, boolean negated) {
        boolean leftDistance = isCall(leftNode, "distanceKm");
        boolean rightDistance = isCall(rightNode, "distanceKm");
        if (leftDistance || rightDistance) {
            if (leftDistance && rightDistance) {
                throw refuse(where, "distanceKm(...) must be compared with a number, $params.name or $user.* value");
            }
            Map<String, Object> call = leftDistance ? leftNode : rightNode;
            if (args(call).size() != 4) {
                throw refuse(where, "distanceKm takes 4 arguments: (lat1, lng1, lat2, lng2)");
            }
            String normalized = leftDistance ? op : flip(op);
            String effectiveCmp = negated ? negate(normalized) : normalized;
            PredicateLiteral.DistanceCmp cmp = switch (effectiveCmp) {
                case "<" -> PredicateLiteral.DistanceCmp.LT;
                case "<=" -> PredicateLiteral.DistanceCmp.LTE;
                case ">" -> PredicateLiteral.DistanceCmp.GT;
                case ">=" -> PredicateLiteral.DistanceCmp.GTE;
                default -> throw refuse(where, "distanceKm(...) can only be compared with < <= > >= "
                        + "(a distance is never exactly equal; use a range or withinKm)");
            };
            return distanceClause(where, args(call), leftDistance ? rightNode : leftNode, cmp);
        }
        Operand left = operand(where, leftNode);
        Operand right = operand(where, rightNode);
        if (left instanceof Operand.Field && !(right instanceof Operand.Field)) {
            // field on the left: as written
        } else if (right instanceof Operand.Field && !(left instanceof Operand.Field)) {
            Operand swap = left;
            left = right;
            right = swap;
            op = flip(op);
        } else {
            throw refuse(where, "a comparison must have a field on one side and a literal, $params.name or $user.* "
                    + "on the other");
        }
        String effective = negated ? negate(op) : op;
        GroupByJoinGrammar.Target target = ((Operand.Field) left).target();
        if (right instanceof Operand.NullLit) {
            if ("==".equals(effective)) {
                return new PredicateClause(target, PredicateOperator.IS_NULL, PredicateLiteral.None.INSTANCE);
            }
            if ("!=".equals(effective)) {
                return new PredicateClause(target, PredicateOperator.IS_NOT_NULL, PredicateLiteral.None.INSTANCE);
            }
            throw refuse(where, "null can only be compared with == or !=");
        }
        return new PredicateClause(target, predicateOperator(effective), ((Operand.Lit) right).literal());
    }

    /** The path of a clause whose four coordinates are all values: it names no field of its own. */
    public static final String DISTANCE_SENTINEL_FIELD = "__distance__";

    private static boolean isCall(Map<String, Object> node, String name) {
        return "call".equals(node.get("k")) && name.equals(String.valueOf(node.get("name")));
    }

    private static PredicateClause distanceClause(
            String where, List<Map<String, Object>> coordinates, Map<String, Object> kmNode,
            PredicateLiteral.DistanceCmp cmp) {
        PredicateLiteral.DistanceOperand[] operands = new PredicateLiteral.DistanceOperand[4];
        GroupByJoinGrammar.Target path = null;
        for (int i = 0; i < 4; i++) {
            Operand operand = operand(where, coordinates.get(i));
            if (operand instanceof Operand.Field field) {
                operands[i] = new PredicateLiteral.DistanceOperand.Field(field.target());
                if (path == null) {
                    path = field.target();
                }
            } else if (operand instanceof Operand.Lit literal) {
                operands[i] = new PredicateLiteral.DistanceOperand.Value(literal.literal());
            } else {
                throw refuse(where, "a distance coordinate cannot be null");
            }
        }
        Operand km = operand(where, kmNode);
        if (!(km instanceof Operand.Lit radius)) {
            throw refuse(where, "the distance to compare with must be a number, $params.name or $user.* value");
        }
        return new PredicateClause(path == null ? new GroupByJoinGrammar.Target.Direct(DISTANCE_SENTINEL_FIELD) : path,
                PredicateOperator.DISTANCE_KM,
                new PredicateLiteral.Distance(operands[0], operands[1], operands[2], operands[3], cmp, radius.literal()));
    }

    /** {@code field in [..]} (a single IN clause) or its negation (an AND of {@code !=} clauses). */
    private static List<List<PredicateClause>> membership(
            String where, boolean notIn, Map<String, Object> fieldNode, Map<String, Object> listNode) {
        Operand field = operand(where, fieldNode);
        if (!(field instanceof Operand.Field fieldOperand)) {
            throw refuse(where, "the left side of 'in' must be a field");
        }
        if (!"list".equals(listNode.get("k"))) {
            throw refuse(where, "the right side of 'in' must be a list literal, e.g. status in ['A', 'B']");
        }
        List<PredicateLiteral> values = new ArrayList<>();
        for (Object item : (List<?>) listNode.get("items")) {
            @SuppressWarnings("unchecked")
            Operand value = operand(where, (Map<String, Object>) item);
            if (!(value instanceof Operand.Lit literal)) {
                throw refuse(where, "an 'in' list may only hold literals, $params.name or $user.* values");
            }
            values.add(literal.literal());
        }
        if (values.isEmpty()) {
            throw refuse(where, "an 'in' list must name at least one value");
        }
        if (!notIn) {
            return List.of(List.of(new PredicateClause(fieldOperand.target(), PredicateOperator.IN,
                    new PredicateLiteral.Values(values))));
        }
        List<PredicateClause> all = new ArrayList<>();
        for (PredicateLiteral value : values) {
            all.add(new PredicateClause(fieldOperand.target(), PredicateOperator.NEQ, value));
        }
        return List.of(all);
    }

    private sealed interface Operand {
        record Field(GroupByJoinGrammar.Target target) implements Operand {
        }

        record Lit(PredicateLiteral literal) implements Operand {
        }

        record NullLit() implements Operand {
        }
    }

    @SuppressWarnings("unchecked")
    private static Operand operand(String where, Map<String, Object> node) {
        String kind = String.valueOf(node.get("k"));
        switch (kind) {
            case "var": {
                String name = String.valueOf(node.get("name"));
                if (name.startsWith("$")) {
                    if (name.startsWith("$params.") && isPlainName(name.substring("$params.".length()))) {
                        return new Operand.Lit(new PredicateLiteral.Placeholder(name.substring("$params.".length())));
                    }
                    if (USER_REFERENCES.contains(name)) {
                        return new Operand.Lit(new PredicateLiteral.UserRef(name));
                    }
                    throw refuse(where, "'" + name + "' is not available in a query where (available: $params.<name>, "
                            + USER_REFERENCES + ")");
                }
                return new Operand.Field(pathTarget(where, name));
            }
            case "lit": {
                String type = String.valueOf(node.get("t"));
                Object value = node.get("v");
                return switch (type) {
                    case "null" -> new Operand.NullLit();
                    case "bool" -> new Operand.Lit(new PredicateLiteral.Value(value));
                    case "str" -> new Operand.Lit(new PredicateLiteral.Value(value));
                    default -> new Operand.Lit(new PredicateLiteral.Value(number(String.valueOf(value))));
                };
            }
            case "un": {
                Map<String, Object> inner = child(node, "a");
                if ("-".equals(node.get("op")) && "lit".equals(inner.get("k")) && "num".equals(inner.get("t"))) {
                    return new Operand.Lit(new PredicateLiteral.Value(number("-" + inner.get("v"))));
                }
                throw refuse(where, "an expression cannot be pushed down into a query where");
            }
            case "call": {
                String function = String.valueOf(node.get("name")).toLowerCase(java.util.Locale.ROOT);
                Object callArgs = node.get("args");
                boolean noArguments = callArgs instanceof List<?> list && list.isEmpty();
                if (noArguments && CLOCK_FUNCTIONS.contains(function)) {
                    return new Operand.Lit(new PredicateLiteral.Clock(function));
                }
                throw refuse(where, "function '" + node.get("name") + "' cannot be pushed down into a query where"
                        + " (only today() and now() can be, as a value to compare a field with)");
            }
            default:
                throw refuse(where, "a " + kindName(kind) + " cannot be pushed down into a query where");
        }
    }

    /**
     * Integers stay {@code Long}; anything with a fraction stays {@code Double} (the existing bound-parameter
     * typing). An integer too large for a {@code Long} stays exact as a {@code BigDecimal} rather than failing.
     */
    private static Object number(String plain) {
        if (plain.indexOf('.') >= 0) {
            return Double.parseDouble(plain);
        }
        try {
            return Long.parseLong(plain);
        } catch (NumberFormatException tooLarge) {
            return new java.math.BigDecimal(plain);
        }
    }

    /** A field name, or a {@code referenceField.targetField} path -- resolved by {@link GroupByJoinGrammar}. */
    private static GroupByJoinGrammar.Target pathTarget(String where, String pathText) {
        try {
            return GroupByJoinGrammar.parse(pathText);
        } catch (GroupByJoinGrammar.UnsupportedGroupByPathException hopFailure) {
            throw refuse(where, "predicate path \"" + pathText + "\" -- " + hopFailure.reason()
                    + " (a path is a field name, or referenceField.targetField up to "
                    + GroupByJoinGrammar.MAX_JOIN_HOPS + " hops)");
        }
    }

    private static boolean isPlainName(String name) {
        if (name.isEmpty() || (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char current = name.charAt(index);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                return false;
            }
        }
        return true;
    }

    private static List<List<PredicateClause>> union(
            String where, List<List<PredicateClause>> a, List<List<PredicateClause>> b) {
        if ((long) a.size() + b.size() > MAX_GROUPS) {
            throw refuse(where, "it expands to more than " + MAX_GROUPS + " OR-groups; simplify the condition");
        }
        List<List<PredicateClause>> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<List<PredicateClause>> cross(
            String where, List<List<PredicateClause>> a, List<List<PredicateClause>> b) {
        if ((long) a.size() * b.size() > MAX_GROUPS) {
            throw refuse(where, "it expands to more than " + MAX_GROUPS + " OR-groups; simplify the condition");
        }
        List<List<PredicateClause>> out = new ArrayList<>();
        for (List<PredicateClause> left : a) {
            for (List<PredicateClause> right : b) {
                List<PredicateClause> joined = new ArrayList<>(left);
                joined.addAll(right);
                out.add(joined);
            }
        }
        return out;
    }

    private static String flip(String op) {
        return switch (op) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> op;
        };
    }

    private static String negate(String op) {
        return switch (op) {
            case "==" -> "!=";
            case "!=" -> "==";
            case "<" -> ">=";
            case "<=" -> ">";
            case ">" -> "<=";
            default -> "<";
        };
    }

    private static PredicateOperator predicateOperator(String op) {
        return switch (op) {
            case "==" -> PredicateOperator.EQ;
            case "!=" -> PredicateOperator.NEQ;
            case "<" -> PredicateOperator.LT;
            case "<=" -> PredicateOperator.LTE;
            case ">" -> PredicateOperator.GT;
            default -> PredicateOperator.GTE;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> node, String key) {
        return (Map<String, Object>) node.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> args(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("args");
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "tern" -> "?: conditional";
            case "lit" -> "bare literal";
            case "list" -> "list";
            case "lambda" -> "lambda";
            default -> kind;
        };
    }

    private static UnsupportedPredicateException refuse(String where, String reason) {
        return new UnsupportedPredicateException(where, null, reason);
    }

    /** field/path (== | != | > | >= | < | <= | contains | startsWith) literal
     *  | field/path "in" "(" literal ("," literal)* ")"
     *  | field/path "is" "null"
     *  | field/path "is" "not" "null" */
    public enum PredicateOperator {
        EQ("=="), NEQ("!="), GTE(">="), LTE("<="), GT(">"), LT("<"),
        /** Case-sensitive substring match, mirrors {@code ConceptQuery.Operator.CONTAINS}. */
        CONTAINS("contains"),
        STARTS_WITH("startsWith"),
        /** {@code field in (lit, lit, ...)} -- {@link PredicateLiteral.Values} carries the list. */
        IN("in"),
        IS_NULL("is null"),
        IS_NOT_NULL("is not null"),
        /**
         * FORM P3-T4: {@code withinKm(lat1, lng1, lat2, lng2, km)} and {@code distanceKm(lat1, lng1, lat2, lng2) <op> km}.
         * {@link PredicateLiteral.Distance} carries the four coordinates and the radius; the clause's
         * {@code path} is the first field operand, or the sentinel {@link #DISTANCE_SENTINEL_FIELD} when all
         * four are values.
         */
        DISTANCE_KM("distanceKm");

        private final String token;

        PredicateOperator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** {@link #IS_NULL}/{@link #IS_NOT_NULL} take no literal on the right. */
        public boolean isUnary() {
            return this == IS_NULL || this == IS_NOT_NULL;
        }
    }

    public sealed interface PredicateLiteral {
        record Value(Object value) implements PredicateLiteral {
        }

        /** An unresolved {@code :name} bind placeholder -- resolution is the CALLER's job, same as v1. */
        record Placeholder(String name) implements PredicateLiteral {
        }

        /**
         * Pigmentampas friction #24: {@code $user.id} / {@code $user.actorId} / {@code $user.tenantId}
         * -- the CALLER's value, resolved per request by the caller (kernel
         * {@code ConceptQueryPredicateCompiler} with an {@code ExecutionContext}), never at compile time.
         */
        record UserRef(String reference) implements PredicateLiteral {
        }

        /**
         * REG-341: {@code today()} / {@code now()} on the right-hand side of a comparison
         * ({@code createdAt >= today()}). Like {@link UserRef} it is resolved by the caller every time the
         * predicate is compiled for a request (kernel {@code ConceptQueryPredicateCompiler}), never frozen here:
         * {@code function} is the lower-case name.
         */
        record Clock(String function) implements PredicateLiteral {
        }

        /** The right-hand side of an {@link PredicateOperator#IN} clause. */
        record Values(List<PredicateLiteral> values) implements PredicateLiteral {
            public Values {
                if (values == null || values.isEmpty()) {
                    throw new IllegalArgumentException("an 'in' list must carry at least one value");
                }
                values = List.copyOf(values);
            }
        }

        /** One coordinate of a {@link Distance}: a field (or reference path) or a value / bind parameter / caller value. */
        sealed interface DistanceOperand {
            record Field(GroupByJoinGrammar.Target target) implements DistanceOperand {
            }

            record Value(PredicateLiteral literal) implements DistanceOperand {
            }
        }

        /** {@code withinKm} is {@code LTE}; {@code distanceKm(...) <op> km} maps to the matching comparison. */
        enum DistanceCmp {
            LT, LTE, GT, GTE
        }

        /** The right-hand side of a {@link PredicateOperator#DISTANCE_KM} clause. */
        record Distance(DistanceOperand lat1, DistanceOperand lng1, DistanceOperand lat2, DistanceOperand lng2,
                        DistanceCmp cmp, PredicateLiteral km) implements PredicateLiteral {
        }

        /** The (non-)literal carried by {@link PredicateOperator#IS_NULL}/{@link PredicateOperator#IS_NOT_NULL}. */
        enum None implements PredicateLiteral {
            INSTANCE
        }
    }

    /**
     * One predicate clause. {@code path} is a {@link GroupByJoinGrammar.Target} -- a plain field
     * ({@code Direct}) or a 1-to-{@link GroupByJoinGrammar#MAX_JOIN_HOPS}-hop reference-path join
     * ({@code Join}), resolved by delegating to {@link GroupByJoinGrammar#parse} so a predicate's
     * left side is bounded by the exact same, already-proven cap {@code groupBy} join paths are.
     */
    public record PredicateClause(GroupByJoinGrammar.Target path, PredicateOperator operator, PredicateLiteral literal) {
    }

    /** The zero-argument date functions a {@code where} may compare a field with ({@link PredicateLiteral.Clock}). */
    public static final java.util.Set<String> CLOCK_FUNCTIONS = java.util.Set.of("today", "now");

    /** The caller values a v2 {@code where} may name on the right-hand side ({@link PredicateLiteral.UserRef}). */
    public static final java.util.Set<String> USER_REFERENCES =
            java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(
                    java.util.Set.of("$user.id", "$user.actorId", "$user.tenantId")));
}