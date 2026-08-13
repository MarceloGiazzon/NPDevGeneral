package com.finalexec.npdev.dto;

public class BusinessCapabilitySelectionRequest {

    private String capabilityId;
    private String targetContextName;
    private String selectedBy;
    private String selectionReason;

    public BusinessCapabilitySelectionRequest() {
    }

    public String getCapabilityId() {
        return capabilityId;
    }

    public void setCapabilityId(String capabilityId) {
        this.capabilityId = capabilityId;
    }

    public String getTargetContextName() {
        return targetContextName;
    }

    public void setTargetContextName(String targetContextName) {
        this.targetContextName = targetContextName;
    }

    public String getSelectedBy() {
        return selectedBy;
    }

    public void setSelectedBy(String selectedBy) {
        this.selectedBy = selectedBy;
    }

    public String getSelectionReason() {
        return selectionReason;
    }

    public void setSelectionReason(String selectionReason) {
        this.selectionReason = selectionReason;
    }
}