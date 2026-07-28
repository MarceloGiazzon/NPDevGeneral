package com.npdev.kernel.concepts;

import java.util.List;

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

    static ConceptGatewaySemanticPolicy noop() {
        return NOOP;
    }
}
