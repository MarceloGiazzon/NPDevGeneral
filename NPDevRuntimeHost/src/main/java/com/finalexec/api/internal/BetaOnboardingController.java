package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.internal.*;

import com.finalexec.npdev.service.experimental.BetaOnboardingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BetaOnboardingController {

    private final BetaOnboardingService betaOnboardingService;

    public BetaOnboardingController(BetaOnboardingService betaOnboardingService) {
        this.betaOnboardingService = betaOnboardingService;
    }

    @GetMapping({"/api/v1/help/beta-onboarding", "/api/help/beta-onboarding"})
    public Map<String, Object> betaOnboarding() {
        return betaOnboardingService.betaOnboarding();
    }

    @GetMapping({"/api/v1/help/supported-features", "/api/help/supported-features"})
    public Map<String, Object> supportedFeatures() {
        return betaOnboardingService.supportedFeatures();
    }

    @GetMapping({"/api/v1/help/known-limitations", "/api/help/known-limitations"})
    public Map<String, Object> knownLimitations() {
        return betaOnboardingService.knownLimitations();
    }
}
