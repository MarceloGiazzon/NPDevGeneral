package com.finalexec.npdev.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

public class SemanticPublicationMappingRequest {

    private String mappingScope;
    private String mappingReference;
    private String draftReference;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> semanticMutationReferences;
    private String requestedBy;
    private String rationale;
    private String tenantId;

    public SemanticPublicationMappingRequest() {
    }

    public String getMappingScope() {
        return mappingScope;
    }

    public void setMappingScope(String mappingScope) {
        this.mappingScope = mappingScope;
    }

    public String getMappingReference() {
        return mappingReference;
    }

    public void setMappingReference(String mappingReference) {
        this.mappingReference = mappingReference;
    }

    public String getDraftReference() {
        return draftReference;
    }

    public void setDraftReference(String draftReference) {
        this.draftReference = draftReference;
    }

    public List<String> getSemanticMutationReferences() {
        return semanticMutationReferences;
    }

    public void setSemanticMutationReferences(List<String> semanticMutationReferences) {
        this.semanticMutationReferences = semanticMutationReferences;
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
