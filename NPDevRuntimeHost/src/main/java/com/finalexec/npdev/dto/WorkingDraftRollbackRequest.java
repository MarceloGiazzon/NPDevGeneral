package com.finalexec.npdev.dto;

public class WorkingDraftRollbackRequest {

    private String rollbackReference;
    private String draftReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String rollbackMode;

    public WorkingDraftRollbackRequest() {
    }

    public String getRollbackReference() {
        return rollbackReference;
    }

    public void setRollbackReference(String rollbackReference) {
        this.rollbackReference = rollbackReference;
    }

    public String getDraftReference() {
        return draftReference;
    }

    public void setDraftReference(String draftReference) {
        this.draftReference = draftReference;
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
