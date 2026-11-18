package com.finalexec.experience;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.expr.StdlibFunctions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * FORM P5-T4: the decisions behind {@code POST /api/x/eval} -- server-side values for the expressions an experience
 * declares but the browser cannot compute ({@code count('Ride', status == 'NEW')}, {@code sum(...)}, {@code exists(...)},
 * {@code avg(...)}). Pure and free of Spring and of any store, so the allowlist, the bounds and the
 * null-on-error rule are unit tested without a context; {@code com.finalexec.api.ExperienceEvalController} is the thin
 * HTTP layer and {@link GatewayConceptReads} the permission-scoped read path.
 *
 * <p><b>This is not an expression console.</b> {@link #allowedExpressions} collects the expression texts the
 * caller's OWN experience (the first one matching their roles) and the screens it reaches declare: navigation
 * {@code badge}, widget {@code value} (a string, the values of a {@code {label: expression}} map, or the {@code value}
 * of each {@code [{label, value}]} point), screen {@code visibleWhen}, action {@code visibleWhen} and
 * {@code countdown.until}. A text must match one of them VERBATIM; anything else answers {@code null}, whatever it
 * says. An experience can therefore only ever ask what its author wrote down.
 *
 * <p><b>Permission scoped.</b> A store call ({@code count}, {@code exists}, {@code sum}, {@code avg}) goes through
 * {@link ConceptReads}, which the runtime backs with the caller's own {@code ConceptGateway} context -- the same
 * list/count path as {@code GET /api/concepts/{concept}/page}: concept permission, tenant and row-level
 * {@code access.read} apply exactly as for the REST API, and a denial is just a {@code null}.
 *
 * <p><b>Bounds.</b> At most {@value #MAX_EXPRESSIONS} expressions per request, {@value #MAX_EXPRESSION_CHARS} characters
 * each, {@value #MAX_STORE_CALLS} store calls per expression, and a per-request time budget; any failure answers
 * {@code null} for that key -- never an exception or a message.
 *
 * <p><b>How an expression is evaluated.</b> The store calls are lifted out of the text (their arguments are raw text:
 * the predicate is handed to the same {@code where} grammar queries use, with {@code $user.*} bound to the caller),
 * each becomes a number or boolean, and the rest is evaluated by the ordinary expression engine with
 * {@code $user} in scope. A store call nested inside another's arguments, a predicate outside the pushable grammar
 * (for example {@code isToday(createdAt)}) and an unknown function all fail closed to {@code null}.
 */
public final class ExperienceEvaluator {

    public static final int MAX_EXPRESSIONS = 25;
    public static final int MAX_EXPRESSION_CHARS = 2000;
    public static final int MAX_STORE_CALLS = 8;
    public static final long DEFAULT_BUDGET_MILLIS = 3000;

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> STORE_FUNCTIONS = Set.of("count", "exists", "sum", "avg");

    /** The permission-scoped store reads, supplied by the runtime. Every method may throw; the evaluator answers null. */
    public interface ConceptReads {
        /** Rows of {@code concept} the caller may read that match {@code where} (null/blank = all). */
        long count(String concept, String where);

        /** {@code sum} or {@code avg} of {@code field} over the rows the caller may read that match {@code where}; null when there is no value. */
        BigDecimal aggregate(String concept, String function, String field, String where);
    }

    /** The authenticated caller, as {@code $user} sees them. */
    public record Caller(String actorId, String tenantId, Collection<String> roles, boolean system) {
        Map<String, Object> asUserMap() {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", actorId);
            user.put("actorId", actorId);
            user.put("tenantId", tenantId);
            user.put("roles", new ArrayList<>(roles == null ? List.of() : new TreeSet<>(roles)));
            user.put("isSystem", system);
            return user;
        }
    }

    private final LongSupplier clockMillis;
    private final long budgetMillis;

    public ExperienceEvaluator() {
        this(System::currentTimeMillis, DEFAULT_BUDGET_MILLIS);
    }

    public ExperienceEvaluator(LongSupplier clockMillis, long budgetMillis) {
        this.clockMillis = clockMillis;
        this.budgetMillis = budgetMillis;
    }

    // ------------------------------------------------------------------ the allowlist

    /**
     * Every expression text the caller's experience and the screens it reaches declare, taken from
     * {@link ExperienceCatalog#me}'s body. Empty when the caller has no experience.
     */
    public static Set<String> allowedExpressions(Map<String, Object> me) {
        Set<String> out = new LinkedHashSet<>();
        if (me == null || !(me.get("experience") instanceof JsonNode experience) || experience.isNull()) {
            return out;
        }
        for (JsonNode entry : iterable(experience.get("navigation"))) {
            add(out, entry.get("badge"));
        }
        Object screens = me.get("screens");
        if (screens instanceof Iterable<?> list) {
            for (Object item : list) {
                if (item instanceof JsonNode screen) {
                    collectScreen(screen, out);
                }
            }
        }
        return out;
    }

    private static void collectScreen(JsonNode screen, Set<String> out) {
        add(out, screen.get("visibleWhen"));
        JsonNode countdown = screen.get("countdown");
        if (countdown != null && countdown.isObject()) {
            add(out, countdown.get("until"));
        }
        for (JsonNode widget : iterable(screen.get("widgets"))) {
            JsonNode value = widget.get("value");
            if (value == null) {
                continue;
            }
            if (value.isTextual()) {
                add(out, value);
            } else if (value.isObject()) {
                value.fields().forEachRemaining(entry -> add(out, entry.getValue()));
            } else if (value.isArray()) {
                for (JsonNode point : value) {
                    add(out, point == null ? null : point.get("value"));
                }
            }
        }
        for (JsonNode action : iterable(screen.get("actions"))) {
            add(out, action.get("visibleWhen"));
        }
    }

    private static void add(Set<String> out, JsonNode text) {
        if (text != null && text.isTextual() && !text.asText().isBlank()) {
            out.add(text.asText());
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return array != null && array.isArray() ? array : List.of();
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * {@code asked} (key to expression text) to {@code values} (the same keys; a number, string, boolean or
     * {@code null}). The caller (the controller) has already enforced {@link #MAX_EXPRESSIONS}.
     */
    public Map<String, Object> evaluate(Map<String, String> asked, Set<String> allowed, Caller caller, ConceptReads reads) {
        Map<String, Object> values = new LinkedHashMap<>();
        long deadline = clockMillis.getAsLong() + budgetMillis;
        Map<String, Object> storeCache = new HashMap<>();
        for (Map.Entry<String, String> entry : asked.entrySet()) {
            String text = entry.getValue();
            Object value = null;
            if (text != null && text.length() <= MAX_EXPRESSION_CHARS && allowed.contains(text)
                    && clockMillis.getAsLong() <= deadline) {
                value = evaluateOne(text, caller, reads, deadline, storeCache);
            }
            values.put(entry.getKey(), value);
        }
        return values;
    }

    private Object evaluateOne(String text, Caller caller, ConceptReads reads, long deadline, Map<String, Object> storeCache) {
        try {
            Lifted lifted = lift(text);
            ComputedExpression.parse(lifted.expression()); // a syntax error never costs a store call
            Map<String, Object> vars = new HashMap<>();
            vars.put("$user", caller.asUserMap());
            for (int i = 0; i < lifted.calls().size(); i++) {
                StoreCall call = lifted.calls().get(i);
                if (clockMillis.getAsLong() > deadline) {
                    return null;
                }
                String cacheKey = call.cacheKey();
                Object result;
                if (storeCache.containsKey(cacheKey)) {
                    result = storeCache.get(cacheKey);
                } else {
                    result = run(call, reads);
                    storeCache.put(cacheKey, result);
                }
                vars.put("__q" + i, result);
            }
            return normalize(ComputedExpression.evaluate(lifted.expression(), vars, StdlibFunctions.registry()));
        } catch (RuntimeException failed) {
            // Deliberately no message and no cause in the answer: a denial, a bad predicate, a timeout and a typo all read "unknown".
            return null;
        }
    }

    private static Object run(StoreCall call, ConceptReads reads) {
        switch (call.function()) {
            case "count":
                return reads.count(call.concept(), call.where());
            case "exists":
                return reads.count(call.concept(), call.where()) > 0;
            default: {
                BigDecimal value = reads.aggregate(call.concept(), call.function(), call.field(), call.where());
                if (value == null && "sum".equals(call.function())) {
                    return BigDecimal.ZERO;
                }
                return value;
            }
        }
    }

    /** A JSON-able scalar, or null. */
    static Object normalize(Object value) {
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return null;
                }
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
            }
            if (number instanceof BigDecimal big) {
                BigDecimal stripped = big.stripTrailingZeros();
                if (stripped.scale() <= 0 && stripped.precision() - stripped.scale() <= 18) {
                    return stripped.longValue();
                }
                return stripped.doubleValue();
            }
            if (number instanceof java.math.BigInteger bigInteger) {
                return bigInteger.bitLength() < 63 ? (Object) bigInteger.longValue() : (Object) bigInteger.doubleValue();
            }
            return number.longValue();
        }
        return null;
    }

    // ------------------------------------------------------------------ lifting the store calls out of the text

    /** One {@code count/exists/sum/avg(...)} call found in an expression. */
    record StoreCall(String function, String concept, String field, String where) {
        String cacheKey() {
            return function + "|" + concept + "|" + field + "|" + where;
        }
    }

    record Lifted(String expression, List<StoreCall> calls) {
    }

    /**
     * Replaces every store call in {@code text} with a {@code __qN} variable and returns the calls. Quotes are
     * honoured exactly as the expression tokenizer does (a quote ends at the next identical quote); only a call
     * outside any string, at an identifier boundary and not a dotted member, is lifted.
     *
     * @throws IllegalArgumentException on a malformed or unsupported call (unbalanced, nested, lambda, too many)
     */
    static Lifted lift(String text) {
        StringBuilder out = new StringBuilder(text.length());
        List<StoreCall> calls = new ArrayList<>();
        int n = text.length();
        int i = 0;
        char quote = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i;
                i++;
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
                String name = text.substring(start, i);
                boolean boundary = start == 0 || (text.charAt(start - 1) != '.' && text.charAt(start - 1) != ':');
                int k = i;
                while (k < n && Character.isWhitespace(text.charAt(k))) {
                    k++;
                }
                if (boundary && STORE_FUNCTIONS.contains(name) && k < n && text.charAt(k) == '(') {
                    int close = matchingParen(text, k);
                    if (close < 0) {
                        throw new IllegalArgumentException("unbalanced call");
                    }
                    if (calls.size() >= MAX_STORE_CALLS) {
                        throw new IllegalArgumentException("too many store calls");
                    }
                    calls.add(buildCall(name, splitArguments(text.substring(k + 1, close))));
                    out.append("__q").append(calls.size() - 1);
                    i = close + 1;
                } else {
                    out.append(name);
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return new Lifted(out.toString(), List.copyOf(calls));
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitArguments(String inner) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        args.add(inner.substring(start).trim());
        return args;
    }

    private static StoreCall buildCall(String function, List<String> args) {
        for (String arg : args) {
            if (arg.isEmpty() || arg.contains("=>")) {
                throw new IllegalArgumentException("unsupported argument");
            }
            for (String nested : STORE_FUNCTIONS) {
                if (arg.matches("(?s).*\\b" + nested + "\\s*\\(.*")) {
                    throw new IllegalArgumentException("nested store call");
                }
            }
        }
        String concept = conceptName(args.get(0));
        switch (function) {
            case "count":
                if (args.size() > 2) {
                    throw new IllegalArgumentException("count(concept, predicate?)");
                }
                return new StoreCall("count", concept, null, args.size() == 2 ? args.get(1) : null);
            case "exists":
                if (args.size() != 2) {
                    throw new IllegalArgumentException("exists(concept, predicate)");
                }
                return new StoreCall("exists", concept, null, args.get(1));
            default: {
                if (args.size() < 2 || args.size() > 3) {
                    throw new IllegalArgumentException(function + "(concept, field, predicate?)");
                }
                String field = args.get(1);
                if (field.length() >= 2 && (field.charAt(0) == '\'' || field.charAt(0) == '"')
                        && field.charAt(field.length() - 1) == field.charAt(0)) {
                    field = field.substring(1, field.length() - 1);
                }
                if (!IDENTIFIER.matcher(field).matches()) {
                    throw new IllegalArgumentException("not a field name");
                }
                return new StoreCall(function.toLowerCase(Locale.ROOT), concept, field, args.size() == 3 ? args.get(2) : null);
            }
        }
    }

    private static String conceptName(String literal) {
        if (literal.length() < 3 || (literal.charAt(0) != '\'' && literal.charAt(0) != '"')
                || literal.charAt(literal.length() - 1) != literal.charAt(0)) {
            throw new IllegalArgumentException("the concept must be a quoted name");
        }
        String name = literal.substring(1, literal.length() - 1);
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("not a concept name");
        }
        return name;
    }
}
