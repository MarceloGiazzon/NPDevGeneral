package com.finalexec.npdev.dto;

public class RichExplainabilityRequest {

    private String transactionReference;
    private String explanationReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String explanationMode;

    public RichExplainabilityRequest() {
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public void setTransactionReference(String transactionReference) {
        this.transactionReference = transactionReference;
    }

    public String getExplanationReference() {
        return explanationReference;
    }

    public void setExplanationReference(String explanationReference) {
        this.explanationReference = explanationReference;
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

    public String getExplanationMode() {
        return explanationMode;
    }

    public void setExplanationMode(String explanationMode) {
        this.explanationMode = explanationMode;
    }
}
