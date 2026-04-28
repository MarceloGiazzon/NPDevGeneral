package com.finalexec.npdev.dto;

public class CompilerCandidateDiffRequest {

    private String diffReference;
    private String changeScope;
    private String changeKind;
    private String transactionReference;
    private String mutationReference;
    private String draftReference;
    private String requestedBy;
    private String rationale;
    private String tenantId;
    private String diffMode;

    public CompilerCandidateDiffRequest() {
    }

    public String getDiffReference() {
        return diffReference;
    }

    public void setDiffReference(String diffReference) {
        this.diffReference = diffReference;
    }

    public String getChangeScope() {
        return changeScope;
    }

    public void setChangeScope(String changeScope) {
        this.changeScope = changeScope;
    }

    public String getChangeKind() {
        return changeKind;
    }

    public void setChangeKind(String changeKind) {
        this.changeKind = changeKind;
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

    public String getDiffMode() {
        return diffMode;
    }

    public void setDiffMode(String diffMode) {
        this.diffMode = diffMode;
    }
}
