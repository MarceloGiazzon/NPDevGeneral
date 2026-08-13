package com.finalexec.npdev.dto;

public class CrossTenantGovernanceRequest {

    private String governanceReference;
    private String requestingTenantId;
    private String targetTenantId;
    private String scope;
    private String requestedBy;
    private String rationale;
    private String governanceMode;

    public CrossTenantGovernanceRequest() {
    }

    public String getGovernanceReference() {
        return governanceReference;
    }

    public void setGovernanceReference(String governanceReference) {
        this.governanceReference = governanceReference;
    }

    public String getRequestingTenantId() {
        return requestingTenantId;
    }

    public void setRequestingTenantId(String requestingTenantId) {
        this.requestingTenantId = requestingTenantId;
    }

    public String getTargetTenantId() {
        return targetTenantId;
    }

    public void setTargetTenantId(String targetTenantId) {
        this.targetTenantId = targetTenantId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
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

    public String getGovernanceMode() {
        return governanceMode;
    }

    public void setGovernanceMode(String governanceMode) {
        this.governanceMode = governanceMode;
    }
}
