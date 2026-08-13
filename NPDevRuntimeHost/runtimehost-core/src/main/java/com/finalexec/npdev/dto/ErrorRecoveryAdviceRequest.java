package com.finalexec.npdev.dto;

public class ErrorRecoveryAdviceRequest {

    private String problemType;
    private String sourceArea;
    private String contextNote;

    public ErrorRecoveryAdviceRequest() {
    }

    public String getProblemType() {
        return problemType;
    }

    public void setProblemType(String problemType) {
        this.problemType = problemType;
    }

    public String getSourceArea() {
        return sourceArea;
    }

    public void setSourceArea(String sourceArea) {
        this.sourceArea = sourceArea;
    }

    public String getContextNote() {
        return contextNote;
    }

    public void setContextNote(String contextNote) {
        this.contextNote = contextNote;
    }
}