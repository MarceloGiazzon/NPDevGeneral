package com.npdev.dsl.v1.ast;

/**
 * R5.3 (Roadmap Collection 2026-08-18): a model-declared document-numbering counter -- the
 * {@code INV-2026-0001} shape no prior DSL feature could express. A field's {@code
 * defaultExpression} calls {@code nextNumber('name')} (parses today via R4.1/RUN-12's widened
 * {@code ComputedExpression} grammar; evaluated specially by {@code
 * ConfiguredConceptGatewaySemanticPolicy}, which recognizes the call and allocates atomically
 * rather than falling through to the generic pure evaluator -- see that class's javadoc).
 *
 * @param name   identifier referenced by {@code nextNumber('name')}. Deliberately NOT
 *               namespace-qualified by pack composition -- same reasoning as {@link
 *               WebhookAst#source()}: the reference is an opaque literal embedded inside another
 *               field's {@code defaultExpression} TEXT, which the pack-composition
 *               reference-rewriting machinery ({@code ModelSourceResolver.rewriteKnownMemberReferenceFields})
 *               rewrites discrete JSON fields, not substrings inside an expression string -- so
 *               qualifying the declaration but never the call site would silently break every
 *               pack-declared sequence. {@code SequenceValidation} instead requires global
 *               uniqueness across the fully-resolved model, the same closed loop {@code
 *               WebhookValidation} runs for {@code source}.
 * @param format render template: {@code {seq}}/{@code {seq:N}} (the running counter, zero-padded
 *               to N digits -- exactly one required), plus optional {@code {year}}/{@code {yy}}/
 *               {@code {month}}/{@code {day}} date tokens resolved at allocation time. Any date
 *               token also partitions the underlying counter (see {@code SequenceNumberFormat}),
 *               so {@code "INV-{year}-{seq:4}"} resets to 1 exactly when the year rolls over.
 * @param scope  {@code "global"} (default): one counter shared by every tenant. {@code "tenant"}:
 *               one independent counter per tenant.
 */
public record SequenceAst(String name, String format, String scope) {
    public SequenceAst {
        scope = (scope == null || scope.isBlank()) ? "global" : scope;
    }
}
