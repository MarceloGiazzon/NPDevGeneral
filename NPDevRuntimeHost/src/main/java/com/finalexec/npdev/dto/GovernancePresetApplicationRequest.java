package com.finalexec.npdev.dto;

public class GovernancePresetApplicationRequest {

    private String policyPackId;
    private String targetContextName;
    private String appliedBy;
    private String applicationReason;

    public GovernancePresetApplicationRequest() {
    }

    public String getPolicyPackId() {
        return policyPackId;
    }

    public void setPolicyPackId(String policyPackId) {
        this.policyPackId = policyPackId;
    }

    public String getTargetContextName() {
        return targetContextName;
    }

    public void setTargetContextName(String targetContextName) {
        this.targetContextName = targetContextName;
    }

    public String getAppliedBy() {
        return appliedBy;
    }

    public void setAppliedBy(String appliedBy) {
        this.appliedBy = appliedBy;
    }

    public String getApplicationReason() {
        return applicationReason;
    }

    public void setApplicationReason(String applicationReason) {
        this.applicationReason = applicationReason;
    }
}