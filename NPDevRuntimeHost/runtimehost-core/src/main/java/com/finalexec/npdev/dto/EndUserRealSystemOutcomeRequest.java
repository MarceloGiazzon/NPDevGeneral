package com.finalexec.npdev.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

public class EndUserRealSystemOutcomeRequest {

    private String outcomeReference;
    private String requestedBy;
    private String tenantId;
    private String changeIntent;
    private String impactMode;
    private String reviewExpectation;
    private String publishIntent;
    private String rollbackMode;
    private Boolean executeRollback;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> includedStructuralScopes;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> includedConcepts;

    public EndUserRealSystemOutcomeRequest() {
    }

    public String getOutcomeReference() {
        return outcomeReference;
    }

    public void setOutcomeReference(String outcomeReference) {
        this.outcomeReference = outcomeReference;
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

    public String getRollbackMode() {
        return rollbackMode;
    }

    public void setRollbackMode(String rollbackMode) {
        this.rollbackMode = rollbackMode;
    }

    public Boolean getExecuteRollback() {
        return executeRollback;
    }

    public void setExecuteRollback(Boolean executeRollback) {
        this.executeRollback = executeRollback;
    }

    public List<String> getIncludedStructuralScopes() {
        return includedStructuralScopes;
    }

    public void setIncludedStructuralScopes(List<String> includedStructuralScopes) {
        this.includedStructuralScopes = includedStructuralScopes;
    }

    public List<String> getIncludedConcepts() {
        return includedConcepts;
    }

    public void setIncludedConcepts(List<String> includedConcepts) {
        this.includedConcepts = includedConcepts;
    }
}
