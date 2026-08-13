package com.finalexec.npdev.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

public class PublicationFailureRecoveryRequest {

    private String transactionReference;
    private String draftReference;
    private String failureStage;
    private String failureClassification;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> rollbackAnchorReferences;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String recoveryPosture;

    public PublicationFailureRecoveryRequest() {
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public void setTransactionReference(String transactionReference) {
        this.transactionReference = transactionReference;
    }

    public String getDraftReference() {
        return draftReference;
    }

    public void setDraftReference(String draftReference) {
        this.draftReference = draftReference;
    }

    public String getFailureStage() {
        return failureStage;
    }

    public void setFailureStage(String failureStage) {
        this.failureStage = failureStage;
    }

    public String getFailureClassification() {
        return failureClassification;
    }

    public void setFailureClassification(String failureClassification) {
        this.failureClassification = failureClassification;
    }

    public List<String> getRollbackAnchorReferences() {
        return rollbackAnchorReferences;
    }

    public void setRollbackAnchorReferences(List<String> rollbackAnchorReferences) {
        this.rollbackAnchorReferences = rollbackAnchorReferences;
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

    public String getRecoveryPosture() {
        return recoveryPosture;
    }

    public void setRecoveryPosture(String recoveryPosture) {
        this.recoveryPosture = recoveryPosture;
    }
}
