package com.finalexec.npdev.dto;

public class SourceMutationRollbackAnchorCreateRequest {

    private String mutationScope;
    private String mutationReference;
    private String beforeStateReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;

    public SourceMutationRollbackAnchorCreateRequest() {
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

    public String getBeforeStateReference() {
        return beforeStateReference;
    }

    public void setBeforeStateReference(String beforeStateReference) {
        this.beforeStateReference = beforeStateReference;
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
