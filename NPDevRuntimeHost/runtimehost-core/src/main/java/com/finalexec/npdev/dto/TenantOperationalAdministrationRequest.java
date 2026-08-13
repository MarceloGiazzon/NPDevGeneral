package com.finalexec.npdev.dto;

public class TenantOperationalAdministrationRequest {

    private String tenantId;
    private String adminViewName;
    private String requestedBy;

    public TenantOperationalAdministrationRequest() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAdminViewName() {
        return adminViewName;
    }

    public void setAdminViewName(String adminViewName) {
        this.adminViewName = adminViewName;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }
}