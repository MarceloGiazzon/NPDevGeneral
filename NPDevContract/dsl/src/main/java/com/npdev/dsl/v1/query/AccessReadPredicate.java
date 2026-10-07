package com.npdev.dsl.v1.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a concept's row-level {@code access.read} rule into a {@code where} predicate in
 * {@link QueryPredicateGrammar}'s v2 grammar, for ONE caller -- so a {@code groupBy}/aggregate
 * query (or a {@code groupBy} join into the concept) can push the row scope down into SQL instead
 * of being refused (lifts the LC-B1/C3 boundary for the supported subset; found on Pigmentampas
 * 2026-10-07: "mosaics published" KPI over a concept whose drafts are private).
 *
 * <p>Supported subset -- anything else is {@link Optional#empty()} and the caller keeps refusing:
 * <ul>
 *   <li>clauses combined with {@code ||} and {@code &&} (no parentheses, {@code &&} binds tighter);</li>
 *   <li>{@code $user.roles.contains('Role')}, optionally negated with {@code !}: a per-caller
 *       constant;</li>
 *   <li>{@code field op $user.id|$user.actorId|$user.tenantId} (either side, {@code ==}/{@code !=}):
 *       the caller's value becomes a quoted literal;</li>
 *   <li>any other clause the v2 grammar accepts ({@code status != 'DRAFT'}, {@code forTrade == true}).</li>
 * </ul>
 */
public final class AccessReadPredicate {

    private AccessReadPredicate() {
    }

    /** ALL = no restriction for this caller; NONE = no row is visible; WHERE = {@link #where()}. */
    public enum Kind { ALL, NONE, WHERE }

    public record Translation(Kind kind, String where) {
        static Translation all() {
            return new Translation(Kind.ALL, null);
        }

        static Translation none() {
            return new Translation(Kind.NONE, null);
        }
    }

    private static final Pattern ROLE_CHECK =
            Pattern.compile("^(!\\s*)?\\$user\\.roles\\.contains\\(\\s*'([^']*)'\\s*\\)$");
    private static final Pattern USER_LEFT =
            Pattern.compile("^(\\$user\\.(?:id|actorId|tenantId))\\s*(==|!=)\\s*(.+)$");
    private static final Pattern USER_RIGHT =
            Pattern.compile("^(.+?)\\s*(==|!=)\\s*(\\$user\\.(?:id|actorId|tenantId))$");

    /**
     * @param accessRead the concept's {@code access.read} text
     * @param pathPrefix reference path from the query's base concept to this concept
     *                   ({@code ""} for the base concept itself, {@code "mosaicId."} for a join)
     * @param userId     the caller's actor id ({@code $user.id} / {@code $user.actorId}), may be null
     * @param tenantId   the caller's tenant id, may be null
     * @param hasRole    case-insensitive role membership of the caller
     */
    public static Optional<Translation> translate(
            String accessRead, String pathPrefix, String userId, String tenantId, Predicate<String> hasRole) {
        if (accessRead == null || accessRead.isBlank()) {
            return Optional.of(Translation.all());
        }
        String prefix = pathPrefix == null ? "" : pathPrefix;
        List<String> keptGroups = new ArrayList<>();
        for (String orPart : splitOutsideQuotes(accessRead.trim(), "||")) {
            List<String> keptClauses = new ArrayList<>();
            boolean groupFalse = false;
            for (String andPart : splitOutsideQuotes(orPart.trim(), "&&")) {
                String clause = andPart.trim();
                if (clause.isEmpty()) {
                    return Optional.empty();
                }
                Matcher role = ROLE_CHECK.matcher(clause);
                if (role.matches()) {
                    boolean holds = hasRole.test(role.group(2));
                    if (role.group(1) != null) {
                        holds = !holds;
                    }
                    if (!holds) {
                        groupFalse = true;
                    }
                    continue;
                }
                String rewritten = clause.contains("$user") ? substituteUser(clause, userId, tenantId) : clause;
                if (rewritten == null || hasStructureOutsideQuotes(rewritten)) {
                    return Optional.empty();
                }
                keptClauses.add(prefix + rewritten);
            }
            if (groupFalse) {
                continue;
            }
            if (keptClauses.isEmpty()) {
                return Optional.of(Translation.all());
            }
            keptGroups.add(String.join(" && ", keptClauses));
        }
        if (keptGroups.isEmpty()) {
            return Optional.of(Translation.none());
        }
        String where = String.join(" || ", keptGroups);
        try {
            QueryPredicateGrammar.parseGroups(where);
        } catch (QueryPredicateGrammar.UnsupportedPredicateException outsideGrammar) {
            return Optional.empty();
        }
        return Optional.of(new Translation(Kind.WHERE, where));
    }

    /** True when {@code accessRead} is inside the supported subset for EVERY possible caller. */
    public static boolean isTranslatable(String accessRead, String pathPrefix) {
        return translate(accessRead, pathPrefix, "u", "t", role -> true).isPresent()
                && translate(accessRead, pathPrefix, "u", "t", role -> false).isPresent();
    }

    private static String substituteUser(String clause, String userId, String tenantId) {
        String field;
        String operator;
        String userTerm;
        Matcher left = USER_LEFT.matcher(clause);
        Matcher right = USER_RIGHT.matcher(clause);
        if (left.matches()) {
            userTerm = left.group(1);
            operator = left.group(2);
            field = left.group(3).trim();
        } else if (right.matches()) {
            field = right.group(1).trim();
            operator = right.group(2);
            userTerm = right.group(3);
        } else {
            return null;
        }
        if (field.contains("$user") || field.contains("'")) {
            return null;
        }
        String value = "$user.tenantId".equals(userTerm) ? tenantId : userId;
        if (value == null) {
            return field + " " + operator + " null";
        }
        if (value.indexOf('\'') >= 0) {
            return null;
        }
        return field + " " + operator + " '" + value + "'";
    }

    /** Parentheses or a bare {@code !} outside quotes are outside the supported subset. */
    private static boolean hasStructureOutsideQuotes(String clause) {
        boolean inQuote = false;
        for (int index = 0; index < clause.length(); index++) {
            char current = clause.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && (current == '(' || current == ')' || current == '$'
                    || (current == '!' && (index + 1 >= clause.length() || clause.charAt(index + 1) != '=')))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitOutsideQuotes(String text, String token) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && text.startsWith(token, index)) {
                parts.add(text.substring(start, index));
                index += token.length() - 1;
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }
}
