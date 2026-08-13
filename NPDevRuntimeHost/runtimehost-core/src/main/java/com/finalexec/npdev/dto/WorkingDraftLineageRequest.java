package com.finalexec.npdev.dto;

public class WorkingDraftLineageRequest {

    private String workingDraftId;
    private String draftSystemName;
    private String parentDraftId;
    private String lineageAction;
    private String requestedBy;
    private String rationale;

    public WorkingDraftLineageRequest() {
    }

    public String getWorkingDraftId() {
        return workingDraftId;
    }

    public void setWorkingDraftId(String workingDraftId) {
        this.workingDraftId = workingDraftId;
    }

    public String getDraftSystemName() {
        return draftSystemName;
    }

    public void setDraftSystemName(String draftSystemName) {
        this.draftSystemName = draftSystemName;
    }

    public String getParentDraftId() {
        return parentDraftId;
    }

    public void setParentDraftId(String parentDraftId) {
        this.parentDraftId = parentDraftId;
    }

    public String getLineageAction() {
        return lineageAction;
    }

    public void setLineageAction(String lineageAction) {
        this.lineageAction = lineageAction;
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