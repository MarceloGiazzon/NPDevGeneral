package com.finalexec.npdev.dto;

public class ExplainabilityBundleRequest {

    private String bundleName;
    private String targetDraftSystemId;
    private String requestedBy;

    public ExplainabilityBundleRequest() {
    }

    public String getBundleName() {
        return bundleName;
    }

    public void setBundleName(String bundleName) {
        this.bundleName = bundleName;
    }

    public String getTargetDraftSystemId() {
        return targetDraftSystemId;
    }

    public void setTargetDraftSystemId(String targetDraftSystemId) {
        this.targetDraftSystemId = targetDraftSystemId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }
}