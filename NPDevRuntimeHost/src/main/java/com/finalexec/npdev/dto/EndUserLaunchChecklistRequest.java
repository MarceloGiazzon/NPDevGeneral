package com.finalexec.npdev.dto;

public class EndUserLaunchChecklistRequest {

    private String checklistName;
    private String targetDraftSystemId;
    private String requestedBy;

    public EndUserLaunchChecklistRequest() {
    }

    public String getChecklistName() {
        return checklistName;
    }

    public void setChecklistName(String checklistName) {
        this.checklistName = checklistName;
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