package com.finalexec.npdev.dto;

public class SourceMutationApprovalDecisionRequest {

    private String mutationScope;
    private String mutationReference;
    private String decision;
    private String requestedBy;
    private String rationale;
    private String tenantId;

    public SourceMutationApprovalDecisionRequest() {
    }

    public String getMutationScope() {
        return mutationScope;
    }

    public void setMutationScope(String mutationScope) {
        this.mutationScope = mutationScope;
    }

    public String getMutationReference() {
        return mutationReference;
    }

    public void setMutationReference(String mutationReference) {
        this.mutationReference = mutationReference;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
