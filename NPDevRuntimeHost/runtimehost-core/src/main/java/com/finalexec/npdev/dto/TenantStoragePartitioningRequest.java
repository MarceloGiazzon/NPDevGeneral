package com.finalexec.npdev.dto;

public class TenantStoragePartitioningRequest {

    private String partitionReference;
    private String tenantId;
    private String scope;
    private String requestedBy;
    private String rationale;
    private String partitionMode;

    public TenantStoragePartitioningRequest() {
    }

    public String getPartitionReference() {
        return partitionReference;
    }

    public void setPartitionReference(String partitionReference) {
        this.partitionReference = partitionReference;
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

    public String getPartitionMode() {
        return partitionMode;
    }

    public void setPartitionMode(String partitionMode) {
        this.partitionMode = partitionMode;
    }
}
