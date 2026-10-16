package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ConversionAst;
import com.npdev.dsl.v1.ast.ConversionLookupMatchAst;
import com.npdev.dsl.v1.ast.ConversionSplitTargetAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B13 conversions: the validation-time twin of {@code ModelCompiler.compileConversions}. The compiler
 * resolves every concept/field a conversion names and throws a bare {@link IllegalArgumentException}
 * when one does not exist; that throw stays as a backstop (some callers, e.g. /model-reload, skip
 * this validator), but an author or the AI repair loop needs a stable code, the JSON path and a
 * suggested fix. Each message is {@code B13:<path> <code>: <detail> -- suggestedFix: <fix>} and
 * {@link ValidationDiagnosticNormalizer} lifts the path and code out of it.
 *
 * <p>Codes: {@code conversion_unknown_concept}, {@code conversion_unknown_field},
 * {@code conversion_missing_field}, {@code conversion_missing_claims}, {@code conversion_missing_match},
 * {@code conversion_too_few_sources}, {@code conversion_missing_when}, {@code conversion_unknown_op}.
 *
 * <p>Field lookup is against the concept's OWN declared fields, exactly like the compiler.
 */
final class ConversionValidation {

    private static final List<String> OPS = List.of("copy", "split", "lookup", "merge", "convert", "coalesce", "case");

    private ConversionValidation() {
    }

    static void validateConversions(ModelAst model, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        List<ConversionAst> conversions = model.getConversions();
        for (int i = 0; i < conversions.size(); i++) {
            ConversionAst conversion = conversions.get(i);
            String base = "conversions[" + i + "]";
            ConceptAst concept = entitiesByLower.get(SemanticValidator.normalize(conversion.concept()));
            if (concept == null) {
                errors.add("B13:" + base + ".concept conversion_unknown_concept: conversion '" + conversion.id()
                        + "' declares concept '" + conversion.concept() + "', which is not a declared concept"
                        + " -- suggestedFix: " + closestConcept(conversion.concept(), entitiesByLower));
                continue;
            }
            if (conversion.javaHook() != null) {
                if (conversion.claims().isEmpty()) {
                    errors.add("B13:" + base + ".claims conversion_missing_claims: conversion '" + conversion.id()
                            + "' declares javaHook but no 'claims'"
                            + " -- suggestedFix: add claims[] naming every field of " + concept.getName()
                            + " the hook writes");
                }
                for (int j = 0; j < conversion.claims().size(); j++) {
                    requireField(conversion, concept, conversion.claims().get(j), base + ".claims[" + j + "]", errors);
                }
                continue;
            }
            switch (conversion.op() == null ? "" : conversion.op()) {
                case "copy", "convert" -> {
                    requireField(conversion, concept, conversion.from(), base + ".from", errors);
                    requireField(conversion, concept, conversion.to(), base + ".to", errors);
                }
                case "split" -> {
                    requireField(conversion, concept, conversion.from(), base + ".from", errors);
                    List<ConversionSplitTargetAst> into = conversion.into();
                    for (int j = 0; j < into.size(); j++) {
                        requireField(conversion, concept, into.get(j).field(), base + ".into[" + j + "].field", errors);
                    }
                }
                case "lookup" -> {
                    ConversionLookupMatchAst match = conversion.match();
                    if (match == null) {
                        errors.add("B13:" + base + ".match conversion_missing_match: conversion '" + conversion.id()
                                + "' has op 'lookup' but declares no 'match'"
                                + " -- suggestedFix: add match {concept, on, equals} to the lookup conversion");
                    } else {
                        ConceptAst matchConcept = entitiesByLower.get(SemanticValidator.normalize(match.concept()));
                        if (matchConcept == null) {
                            errors.add("B13:" + base + ".match.concept conversion_unknown_concept: conversion '"
                                    + conversion.id() + "' declares concept '" + match.concept()
                                    + "', which is not a declared concept"
                                    + " -- suggestedFix: " + closestConcept(match.concept(), entitiesByLower));
                        } else {
                            requireField(conversion, matchConcept, match.on(), base + ".match.on", errors);
                        }
                        requireField(conversion, concept, match.equals(), base + ".match.equals", errors);
                    }
                    requireField(conversion, concept, conversion.set(), base + ".set", errors);
                }
                case "merge", "coalesce" -> {
                    if (conversion.mergeFrom().size() < 2) {
                        errors.add("B13:" + base + ".from conversion_too_few_sources: conversion '" + conversion.id()
                                + "' has op '" + conversion.op() + "' but declares fewer than 2 'from' fields ("
                                + conversion.mergeFrom() + ")"
                                + " -- suggestedFix: list at least two source fields in from[], or use op 'copy'");
                    }
                    for (int j = 0; j < conversion.mergeFrom().size(); j++) {
                        requireField(conversion, concept, conversion.mergeFrom().get(j), base + ".from[" + j + "]", errors);
                    }
                    requireField(conversion, concept, conversion.to(), base + ".to", errors);
                }
                case "case" -> {
                    requireField(conversion, concept, conversion.from(), base + ".from", errors);
                    requireField(conversion, concept, conversion.to(), base + ".to", errors);
                    if (conversion.when().isEmpty()) {
                        errors.add("B13:" + base + ".when conversion_missing_when: conversion '" + conversion.id()
                                + "' has op 'case' but declares no 'when' clauses"
                                + " -- suggestedFix: add at least one when {equals, then} clause");
                    }
                }
                default -> errors.add("B13:" + base + ".op conversion_unknown_op: conversion '" + conversion.id()
                        + "' declares op '" + conversion.op() + "', which is not a recognized conversion op"
                        + " -- suggestedFix: use one of " + OPS);
            }
        }
    }

    private static void requireField(
            ConversionAst conversion, ConceptAst concept, String fieldName, String path, List<String> errors) {
        if (fieldName == null || fieldName.isBlank()) {
            errors.add("B13:" + path + " conversion_missing_field: conversion '" + conversion.id()
                    + "' is missing a field name here"
                    + " -- suggestedFix: name one of the fields of " + concept.getName() + ": "
                    + fieldNames(concept));
            return;
        }
        for (FieldAst field : concept.getFields()) {
            if (field.getName() != null && field.getName().equalsIgnoreCase(fieldName)) {
                return;
            }
        }
        errors.add("B13:" + path + " conversion_unknown_field: conversion '" + conversion.id()
                + "' references field '" + fieldName + "' on concept '" + concept.getName()
                + "', which has no such field"
                + " -- suggestedFix: " + closestField(fieldName, concept));
    }

    private static List<String> fieldNames(ConceptAst concept) {
        List<String> names = new ArrayList<>();
        for (FieldAst field : concept.getFields()) {
            if (field.getName() != null) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private static String closestField(String typed, ConceptAst concept) {
        List<String> names = fieldNames(concept);
        String best = closest(typed, names);
        return best != null
                ? "did you mean field '" + best + "' on " + concept.getName() + "?"
                : "use one of the fields declared on " + concept.getName() + ": " + names;
    }

    private static String closestConcept(String typed, Map<String, ConceptAst> entitiesByLower) {
        List<String> names = new ArrayList<>();
        for (ConceptAst concept : entitiesByLower.values()) {
            names.add(concept.getName());
        }
        String best = closest(typed, names);
        return best != null
                ? "did you mean concept '" + best + "'?"
                : "declare the concept, or use one of: " + names;
    }

    /** Nearest candidate by edit distance, or null when nothing is plausibly a typo of {@code typed}. */
    static String closest(String typed, List<String> candidates) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String lowered = typed.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(lowered, candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best != null && bestDistance <= Math.max(2, typed.length() / 3) ? best : null;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
