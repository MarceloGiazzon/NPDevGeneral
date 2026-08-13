package com.finalexec.npdev.dto;

import java.util.List;

public class GovernanceWorkspaceDecisionRequest {

    private String targetType;
    private String targetReference;
    private String decision;
    private String decidedBy;
    private String rationale;
    private String policyCode;
    private List<String> relatedArtifactReferences;

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetReference() {
        return targetReference;
    }

    public void setTargetReference(String targetReference) {
        this.targetReference = targetReference;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getPolicyCode() {
        return policyCode;
    }

    public void setPolicyCode(String policyCode) {
        this.policyCode = policyCode;
    }

    public List<String> getRelatedArtifactReferences() {
        return relatedArtifactReferences;
    }

    public void setRelatedArtifactReferences(List<String> relatedArtifactReferences) {
        this.relatedArtifactReferences = relatedArtifactReferences;
    }
}
