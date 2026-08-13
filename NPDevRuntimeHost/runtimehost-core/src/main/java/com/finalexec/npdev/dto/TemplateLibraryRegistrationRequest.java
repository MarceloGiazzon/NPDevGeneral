package com.finalexec.npdev.dto;

public class TemplateLibraryRegistrationRequest {

    private String templateId;
    private String title;
    private String versionTag;
    private String owner;
    private String lifecycleStage;
    private String recommendationLevel;
    private String complianceNote;
    private String supportedOnboardingStyle;

    public TemplateLibraryRegistrationRequest() {
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public void setVersionTag(String versionTag) {
        this.versionTag = versionTag;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getLifecycleStage() {
        return lifecycleStage;
    }

    public void setLifecycleStage(String lifecycleStage) {
        this.lifecycleStage = lifecycleStage;
    }

    public String getRecommendationLevel() {
        return recommendationLevel;
    }

    public void setRecommendationLevel(String recommendationLevel) {
        this.recommendationLevel = recommendationLevel;
    }

    public String getComplianceNote() {
        return complianceNote;
    }

    public void setComplianceNote(String complianceNote) {
        this.complianceNote = complianceNote;
    }

    public String getSupportedOnboardingStyle() {
        return supportedOnboardingStyle;
    }

    public void setSupportedOnboardingStyle(String supportedOnboardingStyle) {
        this.supportedOnboardingStyle = supportedOnboardingStyle;
    }
}
