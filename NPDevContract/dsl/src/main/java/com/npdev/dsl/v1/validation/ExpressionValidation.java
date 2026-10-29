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
import com.npdev.dsl.v1.expr.ValueBehaviorFunctions;
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

    /**
     * REG-313 (b): a QUERY parameter's {@code defaultExpression} is evaluated by the runtime when the caller
     * does not supply the parameter, with no record in scope -- so it may use literals and the registered
     * value functions ({@code addDays(today(), -7)}) and nothing else. A PROCEDURE parameter's is refused:
     * procedure inputs are not bound by that rule, so a default there would validate and then be ignored.
     */
    static void validateParameterDefaults(
            String owner, boolean queryParameters, List<ProcedureParameterAst> parameters, List<String> errors) {
        for (ProcedureParameterAst parameter : parameters) {
            String expression = parameter.defaultExpression();
            if (expression == null) {
                continue;
            }
            String here = owner + " parameter " + parameter.name() + ": defaultExpression";
            if (!queryParameters) {
                errors.add(here + " is only supported on query parameters (a procedure's inputs are not bound by it,"
                        + " so it would be ignored) -- suggestedFix: remove defaultExpression, or apply the default"
                        + " in the procedure's steps");
                continue;
            }
            if (parameter.required()) {
                errors.add(here + " cannot be combined with required: true -- a defaulted parameter is optional"
                        + " (the caller may omit it) -- suggestedFix: drop required, or drop defaultExpression");
                continue;
            }
            try {
                java.util.Set<String> fields = ComputedExpression.referencedFields(expression);
                if (!fields.isEmpty()) {
                    errors.add(here + " references " + fields + ", but no record is in scope when a query parameter"
                            + " default is evaluated -- suggestedFix: use literals and functions such as"
                            + " addDays(today(), -7)");
                    continue;
                }
                String unavailable = ValueBehaviorFunctions.firstUnavailable(expression, false);
                if (unavailable != null) {
                    errors.add(here + " calls " + unavailable + "(), which the runtime does not provide here"
                            + " (available: " + ValueBehaviorFunctions.NAMES_SORTED + ") -- suggestedFix: use one of"
                            + " the available functions, or a literal");
                }
            } catch (ComputedExpression.ExpressionException unparseable) {
                errors.add(here + " does not parse: " + unparseable.getMessage()
                        + " -- suggestedFix: correct the expression's syntax, e.g. addDays(today(), -7)");
            }
        }
    }

    static void validateTechnologyNeutrality(ModelAst modelAst, List<String> warnings) {
        for (ConceptAst entity : modelAst.getConcepts()) {
            validateNameAgainstForbiddenKeywords("Entity", entity.getName(), warnings);
            for (FieldAst field : entity.getFields()) {
                validateNameAgainstForbiddenKeywords("Field", field.getName(), warnings);
            }
        }

        for (CapabilityAst capability : modelAst.getCapabilities()) {
            validateNameAgainstForbiddenKeywords("Capability", capability.getName(), warnings);
            validateNameAgainstForbiddenKeywords("Capability type", capability.getType(), warnings);
            for (CapabilityOperationAst operation : capability.getOperations()) {
                validateNameAgainstForbiddenKeywords("Capability operation", operation.getName(), warnings);
            }
        }

        for (EventAst event : modelAst.getEvents()) {
            validateNameAgainstForbiddenKeywords("Event", event.getName(), warnings);
        }

        for (FlowAst flow : modelAst.getFlows()) {
            validateNameAgainstForbiddenKeywords("Flow", flow.getName(), warnings);
            validateFlowStepTechnologyNeutrality(flow.getSteps(), warnings);
        }
    }

    private static void validateFlowStepTechnologyNeutrality(List<StepAst> steps, List<String> warnings) {
        for (StepAst step : steps) {
            validateNameAgainstForbiddenKeywords("Flow step", step.getName(), warnings);
            validateNameAgainstForbiddenKeywords("Capability reference", step.getCapability(), warnings);
            validateNameAgainstForbiddenKeywords("Event reference", step.getEvent(), warnings);
            validateNameAgainstForbiddenKeywords("Await event reference", step.getAwaitEvent(), warnings);

            if (!step.getThenSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getThenSteps(), warnings);
            }
            if (!step.getElseSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getElseSteps(), warnings);
            }
            if (!step.getLoopSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getLoopSteps(), warnings);
            }
        }
    }

    private static void validateNameAgainstForbiddenKeywords(String label, String value, List<String> warnings) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = normalize(value);
        for (String keyword : FORBIDDEN_TECH_KEYWORDS) {
            if (normalized.contains(keyword)) {
                warnings.add(label + " name should be technology-neutral; found keyword '" + keyword
                        + "' in '" + value + "' (a warning only: the check matches the word anywhere in the name,"
                        + " even inside an ordinary word)");
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
