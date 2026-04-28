package com.finalexec.npdev.dto;

public class PublishImportedScenarioDraftRequest {

    private String templateId;
    private String draftSystemName;
    private String sourceOnboardingId;
    private String sourceExecutionId;
    private String sourceAnalysisId;
    private String sourceCorrectionId;

    public PublishImportedScenarioDraftRequest() {
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getDraftSystemName() {
        return draftSystemName;
    }

    public void setDraftSystemName(String draftSystemName) {
        this.draftSystemName = draftSystemName;
    }

    public String getSourceOnboardingId() {
        return sourceOnboardingId;
    }

    public void setSourceOnboardingId(String sourceOnboardingId) {
        this.sourceOnboardingId = sourceOnboardingId;
    }

    public String getSourceExecutionId() {
        return sourceExecutionId;
    }

    public void setSourceExecutionId(String sourceExecutionId) {
        this.sourceExecutionId = sourceExecutionId;
    }

    public String getSourceAnalysisId() {
        return sourceAnalysisId;
    }

    public void setSourceAnalysisId(String sourceAnalysisId) {
        this.sourceAnalysisId = sourceAnalysisId;
    }

    public String getSourceCorrectionId() {
        return sourceCorrectionId;
    }

    public void setSourceCorrectionId(String sourceCorrectionId) {
        this.sourceCorrectionId = sourceCorrectionId;
    }
}