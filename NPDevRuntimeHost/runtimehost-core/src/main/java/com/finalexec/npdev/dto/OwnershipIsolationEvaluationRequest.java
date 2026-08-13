package com.finalexec.npdev.dto;

public class OwnershipIsolationEvaluationRequest {

    private String mode;
    private String userId;
    private String userTenantId;
    private String userDepartment;
    private String recordOwnerId;
    private String recordTenantId;
    private String recordDepartment;
    private boolean admin;

    public OwnershipIsolationEvaluationRequest() {
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserTenantId() {
        return userTenantId;
    }

    public void setUserTenantId(String userTenantId) {
        this.userTenantId = userTenantId;
    }

    public String getUserDepartment() {
        return userDepartment;
    }

    public void setUserDepartment(String userDepartment) {
        this.userDepartment = userDepartment;
    }

    public String getRecordOwnerId() {
        return recordOwnerId;
    }

    public void setRecordOwnerId(String recordOwnerId) {
        this.recordOwnerId = recordOwnerId;
    }

    public String getRecordTenantId() {
        return recordTenantId;
    }

    public void setRecordTenantId(String recordTenantId) {
        this.recordTenantId = recordTenantId;
    }

    public String getRecordDepartment() {
        return recordDepartment;
    }

    public void setRecordDepartment(String recordDepartment) {
        this.recordDepartment = recordDepartment;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }
}