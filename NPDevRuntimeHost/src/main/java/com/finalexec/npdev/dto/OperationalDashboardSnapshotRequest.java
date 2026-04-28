package com.finalexec.npdev.dto;

public class OperationalDashboardSnapshotRequest {

    private String dashboardName;
    private String requestedBy;

    public OperationalDashboardSnapshotRequest() {
    }

    public String getDashboardName() {
        return dashboardName;
    }

    public void setDashboardName(String dashboardName) {
        this.dashboardName = dashboardName;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }
}