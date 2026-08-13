package com.finalexec.npdev.dto;

public class CanonicalSourceValidationRequest {

    private String transactionReference;
    private String mutationReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String validationMode;

    public CanonicalSourceValidationRequest() {
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public void setTransactionReference(String transactionReference) {
        this.transactionReference = transactionReference;
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

    public String getValidationMode() {
        return validationMode;
    }

    public void setValidationMode(String validationMode) {
        this.validationMode = validationMode;
    }
}
