package com.finalexec.npdev.dto;

public class SourceRollbackExecutionRequest {

    private String rollbackReference;
    private String anchorReference;
    private String mutationReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String rollbackMode;

    public SourceRollbackExecutionRequest() {
    }

    public String getRollbackReference() {
        return rollbackReference;
    }

    public void setRollbackReference(String rollbackReference) {
        this.rollbackReference = rollbackReference;
    }

    public String getAnchorReference() {
        return anchorReference;
    }

    public void setAnchorReference(String anchorReference) {
        this.anchorReference = anchorReference;
    }

    public String getMutationReference() {
        return mutationReference;
    }

    public void setMutationReference(String mutationReference) {
        this.mutationReference = mutationReference;
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

    public String getRollbackMode() {
        return rollbackMode;
    }

    public void setRollbackMode(String rollbackMode) {
        this.rollbackMode = rollbackMode;
    }
}
