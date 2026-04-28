package com.finalexec.npdev.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class StructuralWriteBackRequest {

    private String requestType;
    private Map<String, Object> payload = new LinkedHashMap<>();

    public StructuralWriteBackRequest() {
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
    }
}