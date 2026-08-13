package com.finalexec.npdev.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

public class PublicationTransactionRecordRequest {

    private String transactionScope;
    private String transactionReference;
    private String draftReference;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> structuralMappingReferences;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> semanticMappingReferences;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> approvalReferences;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> rollbackAnchorReferences;
    private String requestedBy;
    private String rationale;
    private String tenantId;

    public PublicationTransactionRecordRequest() {
    }

    public String getTransactionScope() {
        return transactionScope;
    }

    public void setTransactionScope(String transactionScope) {
        this.transactionScope = transactionScope;
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

    public List<String> getStructuralMappingReferences() {
        return structuralMappingReferences;
    }

    public void setStructuralMappingReferences(List<String> structuralMappingReferences) {
        this.structuralMappingReferences = structuralMappingReferences;
    }

    public List<String> getSemanticMappingReferences() {
        return semanticMappingReferences;
    }

    public void setSemanticMappingReferences(List<String> semanticMappingReferences) {
        this.semanticMappingReferences = semanticMappingReferences;
    }

    public List<String> getApprovalReferences() {
        return approvalReferences;
    }

    public void setApprovalReferences(List<String> approvalReferences) {
        this.approvalReferences = approvalReferences;
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
}
