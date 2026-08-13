package com.finalexec.npdev.dto;

public class WorkingDraftLifecycleActionRequest {

    private String workingDraftId;
    private String lifecycleAction;
    private String relatedDraftId;
    private String relatedDraftName;
    private String requestedBy;
    private String rationale;

    public WorkingDraftLifecycleActionRequest() {
    }

    public String getWorkingDraftId() {
        return workingDraftId;
    }

    public void setWorkingDraftId(String workingDraftId) {
        this.workingDraftId = workingDraftId;
    }

    public String getLifecycleAction() {
        return lifecycleAction;
    }

    public void setLifecycleAction(String lifecycleAction) {
        this.lifecycleAction = lifecycleAction;
    }

    public String getRelatedDraftId() {
        return relatedDraftId;
    }

    public void setRelatedDraftId(String relatedDraftId) {
        this.relatedDraftId = relatedDraftId;
    }

    public String getRelatedDraftName() {
        return relatedDraftName;
    }

    public void setRelatedDraftName(String relatedDraftName) {
        this.relatedDraftName = relatedDraftName;
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