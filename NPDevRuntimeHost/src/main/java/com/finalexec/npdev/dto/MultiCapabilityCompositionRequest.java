package com.finalexec.npdev.dto;

import java.util.ArrayList;
import java.util.List;

public class MultiCapabilityCompositionRequest {

    private String compositionName;
    private String targetSystemName;
    private List<String> selectedCapabilityIds = new ArrayList<>();
    private String compositionReason;
    private String composedBy;

    public MultiCapabilityCompositionRequest() {
    }

    public String getCompositionName() {
        return compositionName;
    }

    public void setCompositionName(String compositionName) {
        this.compositionName = compositionName;
    }

    public String getTargetSystemName() {
        return targetSystemName;
    }

    public void setTargetSystemName(String targetSystemName) {
        this.targetSystemName = targetSystemName;
    }

    public List<String> getSelectedCapabilityIds() {
        return selectedCapabilityIds;
    }

    public void setSelectedCapabilityIds(List<String> selectedCapabilityIds) {
        this.selectedCapabilityIds = selectedCapabilityIds == null ? new ArrayList<>() : selectedCapabilityIds;
    }

    public String getCompositionReason() {
        return compositionReason;
    }

    public void setCompositionReason(String compositionReason) {
        this.compositionReason = compositionReason;
    }

    public String getComposedBy() {
        return composedBy;
    }

    public void setComposedBy(String composedBy) {
        this.composedBy = composedBy;
    }
}