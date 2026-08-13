package com.finalexec.npdev.dto;

public class TenantQueryEnforcementRequest {

    private String enforcementReference;
    private String tenantId;
    private String scope;
    private String requestedBy;
    private String rationale;
    private String enforcementMode;

    public TenantQueryEnforcementRequest() {
    }

    public String getEnforcementReference() {
        return enforcementReference;
    }

    public void setEnforcementReference(String enforcementReference) {
        this.enforcementReference = enforcementReference;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
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

    public String getEnforcementMode() {
        return enforcementMode;
    }

    public void setEnforcementMode(String enforcementMode) {
        this.enforcementMode = enforcementMode;
    }
}
