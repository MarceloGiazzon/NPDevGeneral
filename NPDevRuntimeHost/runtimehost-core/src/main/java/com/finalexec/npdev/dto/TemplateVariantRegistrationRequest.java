package com.finalexec.npdev.dto;

public class TemplateVariantRegistrationRequest {

    private String baseTemplateId;
    private String variantTemplateId;
    private String title;
    private String versionTag;
    private String owner;
    private String lifecycleStage;
    private String specializationNote;
    private String variantAxis;

    public TemplateVariantRegistrationRequest() {
    }

    public String getBaseTemplateId() {
        return baseTemplateId;
    }

    public void setBaseTemplateId(String baseTemplateId) {
        this.baseTemplateId = baseTemplateId;
    }

    public String getVariantTemplateId() {
        return variantTemplateId;
    }

    public void setVariantTemplateId(String variantTemplateId) {
        this.variantTemplateId = variantTemplateId;
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

    public String getSpecializationNote() {
        return specializationNote;
    }

    public void setSpecializationNote(String specializationNote) {
        this.specializationNote = specializationNote;
    }

    public String getVariantAxis() {
        return variantAxis;
    }

    public void setVariantAxis(String variantAxis) {
        this.variantAxis = variantAxis;
    }
}
