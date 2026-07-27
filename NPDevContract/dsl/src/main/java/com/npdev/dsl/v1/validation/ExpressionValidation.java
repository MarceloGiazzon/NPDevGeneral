package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.EnumOptionAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.FlowScheduleAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.ast.GuidePageGadgetAst;
import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;
import com.npdev.dsl.v1.compiled.GuidePageDefaults;
import com.npdev.dsl.v1.ast.PanelActionAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.PanelDataSourceAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureParameterAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.RuleProfileAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.resolution.ModelResolutionException;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.resolution.ResolvedModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;

/**
 * Semantic validation for technology-neutrality keyword checks, procedure/query parameter name
 * checks, the DSL version check, and the small boolean-expression grammar used by
 * {@code visibleWhen}/{@code enabledWhen}/{@code readonlyWhen}/{@code requiredWhen} interaction
 * conditions (tokenizer + recursive-descent parser).
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) to hold expression/name/keyword-shape checks
 * not owned by a single model section.
 */
final class ExpressionValidation {

    private ExpressionValidation() {
    }

    private static final String SUPPORTED_DSL_VERSION = ModelAst.DEFAULT_DSL_VERSION;

    static final Set<String> FORBIDDEN_TECH_KEYWORDS = Set.of(
            "spring", "jpa", "hibernate", "kafka", "smtp", "rest", "soap"
    );

    private static final Set<String> INTERACTION_BOOLEAN_LITERALS =
            Set.of("true", "false", "null");

    static void validateParameterNames(String owner, List<ProcedureParameterAst> parameters, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (ProcedureParameterAst parameter : parameters) {
            if (!names.add(normalize(parameter.name()))) {
                errors.add(owner + ": duplicate parameter name " + parameter.name());
            }
            if (!hasText(parameter.type()) && parameter.schema() == null) {
                errors.add(owner + " parameter " + parameter.name() + ": type or schema is required");
            }
        }
    }

    static void validateTechnologyNeutrality(ModelAst modelAst, List<String> errors) {
        for (ConceptAst entity : modelAst.getConcepts()) {
            validateNameAgainstForbiddenKeywords("Entity", entity.getName(), errors);
            for (FieldAst field : entity.getFields()) {
                validateNameAgainstForbiddenKeywords("Field", field.getName(), errors);
            }
        }

        for (CapabilityAst capability : modelAst.getCapabilities()) {
            validateNameAgainstForbiddenKeywords("Capability", capability.getName(), errors);
            validateNameAgainstForbiddenKeywords("Capability type", capability.getType(), errors);
            for (CapabilityOperationAst operation : capability.getOperations()) {
                validateNameAgainstForbiddenKeywords("Capability operation", operation.getName(), errors);
            }
        }

        for (EventAst event : modelAst.getEvents()) {
            validateNameAgainstForbiddenKeywords("Event", event.getName(), errors);
        }

        for (FlowAst flow : modelAst.getFlows()) {
            validateNameAgainstForbiddenKeywords("Flow", flow.getName(), errors);
            validateFlowStepTechnologyNeutrality(flow.getSteps(), errors);
        }
    }

    private static void validateFlowStepTechnologyNeutrality(List<StepAst> steps, List<String> errors) {
        for (StepAst step : steps) {
            validateNameAgainstForbiddenKeywords("Flow step", step.getName(), errors);
            validateNameAgainstForbiddenKeywords("Capability reference", step.getCapability(), errors);
            validateNameAgainstForbiddenKeywords("Event reference", step.getEvent(), errors);
            validateNameAgainstForbiddenKeywords("Await event reference", step.getAwaitEvent(), errors);

            if (!step.getThenSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getThenSteps(), errors);
            }
            if (!step.getElseSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getElseSteps(), errors);
            }
            if (!step.getLoopSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getLoopSteps(), errors);
            }
        }
    }

    private static void validateNameAgainstForbiddenKeywords(String label, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = normalize(value);
        for (String keyword : FORBIDDEN_TECH_KEYWORDS) {
            if (normalized.contains(keyword)) {
                errors.add(label + " name must be technology-neutral; found forbidden keyword '" + keyword
                        + "' in '" + value + "'");
                return;
            }
        }
    }

    static void validateDslVersion(ModelAst modelAst, List<String> errors) {
        String dslVersion = modelAst.getDslVersion();
        if (dslVersion == null || dslVersion.isBlank()) {
            errors.add("Model dslVersion is required and must be " + SUPPORTED_DSL_VERSION);
            return;
        }
        if (!SUPPORTED_DSL_VERSION.equals(dslVersion.trim())) {
            errors.add("Unsupported dslVersion " + dslVersion + "; supported value is " + SUPPORTED_DSL_VERSION);
        }
    }

    static InteractionExpressionAnalysis analyzeInteractionExpression(String expression) {
        List<InteractionToken> tokens = tokenizeInteractionExpression(expression);
        if (tokens == null || tokens.isEmpty()) {
            return new InteractionExpressionAnalysis(false, List.of(), "expression must be non-blank");
        }
        InteractionExpressionParser parser = new InteractionExpressionParser(tokens);
        return parser.parse();
    }

    private static List<InteractionToken> tokenizeInteractionExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<InteractionToken> tokens = new ArrayList<>();
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '(') {
                tokens.add(new InteractionToken(InteractionTokenType.LPAREN, "("));
                index++;
                continue;
            }
            if (current == ')') {
                tokens.add(new InteractionToken(InteractionTokenType.RPAREN, ")"));
                index++;
                continue;
            }
            if (current == '&' && index + 1 < expression.length() && expression.charAt(index + 1) == '&') {
                tokens.add(new InteractionToken(InteractionTokenType.AND, "&&"));
                index += 2;
                continue;
            }
            if (current == '|' && index + 1 < expression.length() && expression.charAt(index + 1) == '|') {
                tokens.add(new InteractionToken(InteractionTokenType.OR, "||"));
                index += 2;
                continue;
            }
            if (current == '!' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.NE, "!="));
                index += 2;
                continue;
            }
            if (current == '!') {
                tokens.add(new InteractionToken(InteractionTokenType.NOT, "!"));
                index++;
                continue;
            }
            if (current == '=' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.EQ, "=="));
                index += 2;
                continue;
            }
            if (current == '>' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.GE, ">="));
                index += 2;
                continue;
            }
            if (current == '<' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.LE, "<="));
                index += 2;
                continue;
            }
            if (current == '>') {
                tokens.add(new InteractionToken(InteractionTokenType.GT, ">"));
                index++;
                continue;
            }
            if (current == '<') {
                tokens.add(new InteractionToken(InteractionTokenType.LT, "<"));
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                int end = readQuotedLiteral(expression, index);
                if (end < 0) {
                    return null;
                }
                tokens.add(new InteractionToken(
                        InteractionTokenType.STRING,
                        expression.substring(index, end + 1)
                ));
                index = end + 1;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < expression.length()
                        && (Character.isDigit(expression.charAt(end)) || expression.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new InteractionToken(InteractionTokenType.NUMBER, expression.substring(index, end)));
                index = end;
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int end = index + 1;
                while (end < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) {
                    end++;
                }
                String token = expression.substring(index, end);
                String normalized = normalize(token);
                if (INTERACTION_BOOLEAN_LITERALS.contains(normalized)) {
                    tokens.add(new InteractionToken(InteractionTokenType.LITERAL, token));
                } else {
                    tokens.add(new InteractionToken(InteractionTokenType.IDENT, token));
                }
                index = end;
                continue;
            }
            return null;
        }
        return tokens;
    }

    private static int readQuotedLiteral(String expression, int start) {
        char quote = expression.charAt(start);
        for (int index = start + 1; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == quote && expression.charAt(index - 1) != '\\') {
                return index;
            }
        }
        return -1;
    }

    record InteractionExpressionAnalysis(boolean valid, List<String> references, String error) {
        InteractionExpressionAnalysis {
            references = references == null ? List.of() : List.copyOf(new ArrayList<>(references));
        }
    }

    private record InteractionToken(InteractionTokenType type, String text) {
    }

    private enum InteractionTokenType {
        IDENT,
        STRING,
        NUMBER,
        LITERAL,
        LPAREN,
        RPAREN,
        AND,
        OR,
        NOT,
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE
    }

    private static final class InteractionExpressionParser {
        private final List<InteractionToken> tokens;
        private final LinkedHashSet<String> references = new LinkedHashSet<>();
        private int index;
        private String error;

        private InteractionExpressionParser(List<InteractionToken> tokens) {
            this.tokens = tokens == null ? List.of() : tokens;
        }

        private InteractionExpressionAnalysis parse() {
            if (tokens.isEmpty()) {
                return new InteractionExpressionAnalysis(false, List.of(), "expression must be non-blank");
            }
            parseOrExpression();
            if (error == null && index < tokens.size()) {
                error = "unexpected token " + tokens.get(index).text();
            }
            return new InteractionExpressionAnalysis(error == null, List.copyOf(references), error);
        }

        private void parseOrExpression() {
            parseAndExpression();
            while (error == null && match(InteractionTokenType.OR)) {
                parseAndExpression();
            }
        }

        private void parseAndExpression() {
            parseUnaryExpression();
            while (error == null && match(InteractionTokenType.AND)) {
                parseUnaryExpression();
            }
        }

        private void parseUnaryExpression() {
            if (match(InteractionTokenType.NOT)) {
                parseUnaryExpression();
                return;
            }
            parseComparisonExpression();
        }

        private void parseComparisonExpression() {
            parsePrimaryExpression();
            if (error != null) {
                return;
            }
            if (match(InteractionTokenType.EQ)
                    || match(InteractionTokenType.NE)
                    || match(InteractionTokenType.GT)
                    || match(InteractionTokenType.GE)
                    || match(InteractionTokenType.LT)
                    || match(InteractionTokenType.LE)) {
                parsePrimaryExpression();
            }
        }

        private void parsePrimaryExpression() {
            if (match(InteractionTokenType.LPAREN)) {
                parseOrExpression();
                if (!match(InteractionTokenType.RPAREN) && error == null) {
                    error = "missing closing parenthesis";
                }
                return;
            }
            InteractionToken token = current();
            if (token == null) {
                error = "unexpected end of expression";
                return;
            }
            if (token.type() == InteractionTokenType.IDENT) {
                references.add(token.text());
                index++;
                return;
            }
            if (token.type() == InteractionTokenType.STRING
                    || token.type() == InteractionTokenType.NUMBER
                    || token.type() == InteractionTokenType.LITERAL) {
                index++;
                return;
            }
            error = "unexpected token " + token.text();
        }

        private boolean match(InteractionTokenType type) {
            InteractionToken token = current();
            if (token == null || token.type() != type) {
                return false;
            }
            index++;
            return true;
        }

        private InteractionToken current() {
            if (index >= tokens.size()) {
                return null;
            }
            return tokens.get(index);
        }
    }

}
