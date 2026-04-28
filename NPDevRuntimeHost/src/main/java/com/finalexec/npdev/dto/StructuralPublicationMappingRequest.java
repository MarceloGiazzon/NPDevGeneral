package com.finalexec.npdev.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

public class StructuralPublicationMappingRequest {

    private String publicationBatchId;
    private String tenantId;
    private String requestedBy;
    private String rationale;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> sourceMutationReferences;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> draftReferences;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> includedStructuralScopes;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> includedConcepts;

    public StructuralPublicationMappingRequest() {
    }

    public String getPublicationBatchId() {
        return publicationBatchId;
    }

    public void setPublicationBatchId(String publicationBatchId) {
        this.publicationBatchId = publicationBatchId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
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

    public List<String> getSourceMutationReferences() {
        return sourceMutationReferences;
    }

    public void setSourceMutationReferences(List<String> sourceMutationReferences) {
        this.sourceMutationReferences = sourceMutationReferences;
    }

    public List<String> getDraftReferences() {
        return draftReferences;
    }

    public void setDraftReferences(List<String> draftReferences) {
        this.draftReferences = draftReferences;
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
