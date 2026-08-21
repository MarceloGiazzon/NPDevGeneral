package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BetaOnboardingService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-beta-onboarding/beta-onboarding-rules.json";

    private final ObjectMapper objectMapper;

    public BetaOnboardingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> betaOnboarding() {
        Map<String, Object> rules = loadRules();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Beta Onboarding and Guided Help"));
        response.put("recommendedFirstWorkflow", rules.getOrDefault("recommendedFirstWorkflow", List.of()));
        response.put("firstRunMessage", "Start with supported draft and inspection workflows before treating the beta as a full production builder.");
        response.put("nextBestSurfaceLinks", List.of(
                link("Flow Builder", "/flow-builder"),
                link("Architecture Visualization", "/architecture-visualization"),
                link("Change Impact Visualization", "/change-impact-visualization"),
                link("Execution Monitor", "/execution-monitor"),
                link("Governance Workspace", "/governance-workspace")
        ));
        return response;
    }

    public Map<String, Object> supportedFeatures() {
        Map<String, Object> rules = loadRules();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Beta Onboarding and Guided Help"));
        response.put("supported", rules.getOrDefault("supportedFeatures", List.of()));
        response.put("partial", rules.getOrDefault("partialFeatures", List.of()));
        response.put("guided", rules.getOrDefault("guidedFeatures", List.of()));
        response.put("notReady", rules.getOrDefault("notReadyFeatures", List.of()));
        return response;
    }

    public Map<String, Object> knownLimitations() {
        Map<String, Object> rules = loadRules();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Beta Onboarding and Guided Help"));
        response.put("knownLimitations", rules.getOrDefault("notReadyFeatures", List.of()));
        response.put("blockedStateGuidance", rules.getOrDefault("blockedStateGuidance", List.of()));
        response.put("helpPosture", "Clear next-step guidance over generic reassurance.");
        return response;
    }

    private Map<String, Object> link(String label, String path) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("path", path);
        return item;
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load beta onboarding rules.", e);
        }
    }
}
