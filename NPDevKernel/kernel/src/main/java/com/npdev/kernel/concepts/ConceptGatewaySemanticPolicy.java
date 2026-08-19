package com.npdev.kernel.concepts;

import java.util.List;
import java.util.Optional;

public interface ConceptGatewaySemanticPolicy {
    ConceptGatewaySemanticPolicy NOOP = new ConceptGatewaySemanticPolicy() {
    };

    default ConceptSemanticDecision normalizeAndValidate(ConceptGatewayRequestContext request) {
        return ConceptSemanticDecision.allow(request.data());
    }

    default ConceptSemanticDecision applyDefaultsAndDerivedValues(ConceptGatewayRequestContext request) {
        return ConceptSemanticDecision.allow(request.data());
    }

    default ConceptSemanticDecision validateLifecycleTransition(ConceptGatewayRequestContext request) {
        return ConceptSemanticDecision.allow(request.data());
    }

    default ConceptSemanticDecision evaluateRuleProfiles(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles
    ) {
        return ConceptSemanticDecision.allow(request.data()).withRuleProfiles(ruleProfiles);
    }

    default ConceptRecord filterVisibleFields(ConceptRecord record, ConceptGatewayRequestContext request) {
        return record;
    }

    /**
     * LNCH-13: row-level (data-scoped) read authorization -- does the caller's declared
     * {@code access.read} rule (if any) allow this specific record to be visible to them?
     * Called per-record for read/list/query; a {@code false} result means the record is treated
     * as though it doesn't exist (not found for {@code read}, silently omitted for
     * {@code list}/{@code query}) -- worded like "not found" rather than "forbidden" so a denial
     * never confirms a row exists in another user's scope.
     */
    default boolean isRowReadable(ConceptRecord record, ConceptGatewayRequestContext request) {
        return true;
    }

    /**
     * LNCH-13: row-level (data-scoped) write authorization -- does the caller's declared
     * {@code access.write} rule (if any) allow this save/delete to proceed? Evaluated against
     * the record being affected -- the previous record for an update/delete (so a caller can't
     * modify/delete a row outside their own scope), or the incoming data for a create (so a
     * caller can't create a row claiming ownership outside their own scope).
     */
    default boolean isRowWritable(ConceptGatewayRequestContext request) {
        return true;
    }

    /**
     * REG-42 (LNCH13-F3, REG-16-resid Round 2 follow-up): does this concept declare an
     * {@code access.read} row-level rule at all? {@link DefaultConceptGateway#query} uses this to
     * decide whether {@code total}/{@code hasMore} need recomputing against the row-scoped result
     * set (an extra query cost) instead of trusting the store's pre-row-scope count -- a caller
     * whose {@code access.read} excludes most of a tenant's rows must not learn the tenant's full
     * row count via pagination metadata. {@code false} (the default) means no extra cost for a
     * concept that declares no read scope at all.
     */
    default boolean hasRowReadScope(String conceptName) {
        return false;
    }

    /**
     * S4 (roadmap B27, ADR-0011 D1): resolves a declared {@code reference} field's join TARGET
     * concept name -- empty when {@code conceptName} is unknown or {@code fieldName} isn't a
     * reference field. Lets {@link DefaultConceptGateway#aggregate} widen its {@link
     * #hasRowReadScope} hard stop to a {@code groupBy} join's WHOLE path (C3): a group total
     * computed by joining through a field is exactly as much of an access.read leak as one computed
     * directly on a restricted concept. The default (empty) preserves today's behavior for a policy
     * with no model to resolve against (the NOOP policy) -- it simply finds nothing to widen the
     * check to, same as before this method existed.
     */
    default Optional<String> resolveReferenceTarget(String conceptName, String fieldName) {
        return Optional.empty();
    }

    /**
     * R5.5: field-level (not row-level) write authorization -- the names of this request's
     * CHANGED fields (relative to the previous record for an update/delete, or every non-blank
     * field for a create -- the same "previous record if present, else incoming data" subject
     * {@link #isRowWritable} already uses) that are denied by a declared {@code field.access.write}
     * rule. A field the caller did not submit, or submitted with the SAME value it already had, is
     * never included -- only a genuinely attempted change can be denied, so a client that resends
     * an unchanged read-only field (the common shape of a PUT that round-trips the whole record)
     * is never rejected for a field it never actually tried to change. Empty means every changed
     * field passes (or no field on this concept declares a field-level write rule at all) -- the
     * default here preserves today's behavior for the NOOP policy and any policy that predates
     * field-level access.
     */
    default List<String> deniedWriteFields(ConceptGatewayRequestContext request) {
        return List.of();
    }

    static ConceptGatewaySemanticPolicy noop() {
        return NOOP;
    }
}
