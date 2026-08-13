package com.finalexec.npdev.dto;

public class BetaReadinessConsolidationRequest {

    private String reviewName;
    private String requestedBy;

    public BetaReadinessConsolidationRequest() {
    }

    public String getReviewName() {
        return reviewName;
    }

    public void setReviewName(String reviewName) {
        this.reviewName = reviewName;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }
}