package com.finalexec.npdev.dto;

public class CompilerImpactClassificationRequest {

    private String classificationReference;
    private String diffReference;
    private String graphReference;
    private String changeScope;
    private String transactionReference;
    private String mutationReference;
    private String draftReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String classificationMode;

    public CompilerImpactClassificationRequest() {
    }

    public String getClassificationReference() {
        return classificationReference;
    }

    public void setClassificationReference(String classificationReference) {
        this.classificationReference = classificationReference;
    }

    public String getDiffReference() {
        return diffReference;
    }

    public void setDiffReference(String diffReference) {
        this.diffReference = diffReference;
    }

    public String getGraphReference() {
        return graphReference;
    }

    public void setGraphReference(String graphReference) {
        this.graphReference = graphReference;
    }

    public String getChangeScope() {
        return changeScope;
    }

    public void setChangeScope(String changeScope) {
        this.changeScope = changeScope;
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

    public String getClassificationMode() {
        return classificationMode;
    }

    public void setClassificationMode(String classificationMode) {
        this.classificationMode = classificationMode;
    }
}
