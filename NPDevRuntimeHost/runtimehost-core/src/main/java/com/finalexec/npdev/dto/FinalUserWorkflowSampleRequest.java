package com.finalexec.npdev.dto;

public class FinalUserWorkflowSampleRequest {

    private String workflowReference;
    private String requestedBy;
    private String tenantId;
    private String changeIntent;
    private String impactMode;
    private String reviewExpectation;
    private String publishIntent;
    private String rollbackIntent;

    public FinalUserWorkflowSampleRequest() {
    }

    public String getWorkflowReference() {
        return workflowReference;
    }

    public void setWorkflowReference(String workflowReference) {
        this.workflowReference = workflowReference;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getChangeIntent() {
        return changeIntent;
    }

    public void setChangeIntent(String changeIntent) {
        this.changeIntent = changeIntent;
    }

    public String getImpactMode() {
        return impactMode;
    }

    public void setImpactMode(String impactMode) {
        this.impactMode = impactMode;
    }

    public String getReviewExpectation() {
        return reviewExpectation;
    }

    public void setReviewExpectation(String reviewExpectation) {
        this.reviewExpectation = reviewExpectation;
    }

    public String getPublishIntent() {
        return publishIntent;
    }

    public void setPublishIntent(String publishIntent) {
        this.publishIntent = publishIntent;
    }

    public String getRollbackIntent() {
        return rollbackIntent;
    }

    public void setRollbackIntent(String rollbackIntent) {
        this.rollbackIntent = rollbackIntent;
    }
}
