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

    static ConceptGatewaySemanticPolicy noop() {
        return NOOP;
    }
}
