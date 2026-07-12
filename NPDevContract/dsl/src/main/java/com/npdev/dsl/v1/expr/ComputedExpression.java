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
    }

    /** Parse an expression into an AST, or throw {@link ExpressionException}. */
    public static Node parse(String expression) {
        return new Parser(tokenize(expression)).parseAll();
    }

    /** Parse-only syntax check; throws {@link ExpressionException} if invalid. */
    public static void validate(String expression) {
        parse(expression);
    }

    /** Parse and evaluate against the given variables. */
    public static Object evaluate(String expression, Map<String, Object> vars) {
        return parse(expression).eval(vars);
    }

    // ---- AST ---------------------------------------------------------------

    private record Literal(Object value) implements Node {
        public Object eval(Map<String, Object> vars) {
            return value;
        }
    }

    private record Var(String name) implements Node {
        public Object eval(Map<String, Object> vars) {
            return vars == null ? null : vars.get(name);
        }
    }

    private record Unary(String op, Node operand) implements Node {
        public Object eval(Map<String, Object> vars) {
            Object v = operand.eval(vars);
            return "!".equals(op) ? !truthy(v) : number(-toNumber(v));
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        public Object eval(Map<String, Object> vars) {
            switch (op) {
                case "&&": return truthy(left.eval(vars)) && truthy(right.eval(vars));
                case "||": return truthy(left.eval(vars)) || truthy(right.eval(vars));
                default: break;
            }
            Object l = left.eval(vars);
            Object r = right.eval(vars);
            switch (op) {
                case "+":
                    // Arithmetic when both sides are numeric (incl. numeric strings); else concatenate.
                    if (isNumericLike(l) && isNumericLike(r)) {
                        return number(toNumber(l) + toNumber(r));
                    }
                    return stringify(l) + stringify(r);
                case "-": return number(toNumber(l) - toNumber(r));
                case "*": return number(toNumber(l) * toNumber(r));
                case "/": { double d = toNumber(r); return number(d == 0 ? 0 : toNumber(l) / d); }
                case "%": { double d = toNumber(r); return number(d == 0 ? 0 : toNumber(l) % d); }
                case "==": return equalsLoose(l, r);
                case "!=": return !equalsLoose(l, r);
                case "<": return toNumber(l) < toNumber(r);
                case "<=": return toNumber(l) <= toNumber(r);
                case ">": return toNumber(l) > toNumber(r);
                case ">=": return toNumber(l) >= toNumber(r);
                default: throw new ExpressionException("unknown operator: " + op);
            }
        }
    }

    // ---- Coercion ----------------------------------------------------------

    private static boolean isNumericLike(Object v) {
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

    private static double toNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** Return a whole double as Long (so totals render without a trailing .0), else Double. */
    private static Object number(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return (long) d;
        }
        return d;
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
        if (l instanceof Number || r instanceof Number) {
            return toNumber(l) == toNumber(r);
        }
        return stringify(l).equals(stringify(r));
    }

    private static String stringify(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    // ---- Tokenizer ---------------------------------------------------------

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
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token("IDENT", s.substring(start, i)));
                continue;
            }
            // multi-char operators first
            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||")) {
                tokens.add(new Token("OP", two));
                i += 2;
                continue;
            }
            if ("+-*/%<>!()".indexOf(c) >= 0) {
                tokens.add(new Token(c == '(' ? "LP" : c == ')' ? "RP" : "OP", String.valueOf(c)));
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
            Node node = parseOr();
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
                        return new Literal(number(Double.parseDouble(t.text())));
                    } catch (NumberFormatException e) {
                        throw new ExpressionException("invalid number: " + t.text());
                    }
                case "STR":
                    next();
                    return new Literal(t.text());
                case "IDENT":
                    next();
                    switch (t.text()) {
                        case "true": return new Literal(Boolean.TRUE);
                        case "false": return new Literal(Boolean.FALSE);
                        case "null": return new Literal(null);
                        default: return new Var(t.text());
                    }
                case "LP":
                    next();
                    Node inner = parseOr();
                    expect("RP");
                    return inner;
                default:
                    throw new ExpressionException("unexpected token '" + t.text() + "' in expression");
            }
        }
    }
}
