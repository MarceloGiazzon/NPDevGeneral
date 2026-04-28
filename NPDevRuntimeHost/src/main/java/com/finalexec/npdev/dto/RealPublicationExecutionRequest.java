package com.finalexec.npdev.dto;

public class RealPublicationExecutionRequest {

    private String transactionReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String publicationMode;

    public RealPublicationExecutionRequest() {
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public void setTransactionReference(String transactionReference) {
        this.transactionReference = transactionReference;
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

    public String getPublicationMode() {
        return publicationMode;
    }

    public void setPublicationMode(String publicationMode) {
        this.publicationMode = publicationMode;
    }
}
