package com.finalexec.npdev.dto;

public class SemanticImpactPreviewRequest {

    private String requestId;
    private String changeKind;
    private String targetScope;
    private String requestedBy;
    private String summary;
    private Integer estimatedTouchedArtifacts;

    public SemanticImpactPreviewRequest() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getChangeKind() {
        return changeKind;
    }

    public void setChangeKind(String changeKind) {
        this.changeKind = changeKind;
    }

    public String getTargetScope() {
        return targetScope;
    }

    public void setTargetScope(String targetScope) {
        this.targetScope = targetScope;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getEstimatedTouchedArtifacts() {
        return estimatedTouchedArtifacts;
    }

    public void setEstimatedTouchedArtifacts(Integer estimatedTouchedArtifacts) {
        this.estimatedTouchedArtifacts = estimatedTouchedArtifacts;
    }
}