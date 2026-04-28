package com.finalexec.npdev.dto;

public class SemanticGovernanceActionRequest {

    private String governanceId;
    private String comment;

    public SemanticGovernanceActionRequest() {
    }

    public String getGovernanceId() {
        return governanceId;
    }

    public void setGovernanceId(String governanceId) {
        this.governanceId = governanceId;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}