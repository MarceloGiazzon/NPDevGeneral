package com.finalexec.npdev.dto;

public class TenantNativeGovernanceRequest {

    private String tenantId;
    private String tenantAction;
    private String targetScope;
    private String requestedBy;
    private String rationale;

    public TenantNativeGovernanceRequest() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantAction() {
        return tenantAction;
    }

    public void setTenantAction(String tenantAction) {
        this.tenantAction = tenantAction;
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

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }
}