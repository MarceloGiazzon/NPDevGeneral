package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimalVersionSelectorTest {

    private static MinimalVersionSelector.Requirement requirement(String requirer, String path, String constraint) {
        return new MinimalVersionSelector.Requirement(
                requirer, List.of(path.split(">")), PackVersionConstraint.parse(constraint));
    }

    @Test
    void noRequirementsTriviallySelectsTheLocalVersion() {
        MinimalVersionSelector.Result result = MinimalVersionSelector.select("user", List.of(), new PackVersion(2, 5, 0));
        assertInstanceOf(MinimalVersionSelector.Selected.class, result);
        assertEquals(new PackVersion(2, 5, 0), ((MinimalVersionSelector.Selected) result).resolvedVersion());
    }

    @Test
    void sameMajorDiamondSelectsTheLocalVersionWhenItSatisfiesTheHighestMinimum() {
        List<MinimalVersionSelector.Requirement> requirements = List.of(
                requirement("crm", "app>crm", "^2.0"),
                requirement("billing", "app>billing", "^2.3"));
        MinimalVersionSelector.Result result = MinimalVersionSelector.select("user", requirements, new PackVersion(2, 5, 0));
        assertInstanceOf(MinimalVersionSelector.Selected.class, result);
        assertEquals(new PackVersion(2, 5, 0), ((MinimalVersionSelector.Selected) result).resolvedVersion());
    }

    @Test
    void requirementOrderDoesNotChangeTheResult() {
        List<MinimalVersionSelector.Requirement> forward = List.of(
                requirement("crm", "app>crm", "^2.0"), requirement("billing", "app>billing", "^2.3"));
        List<MinimalVersionSelector.Requirement> reversed = List.of(
                requirement("billing", "app>billing", "^2.3"), requirement("crm", "app>crm", "^2.0"));
        PackVersion local = new PackVersion(2, 5, 0);
        assertEquals(MinimalVersionSelector.select("user", forward, local), MinimalVersionSelector.select("user", reversed, local));
    }

    @Test
    void crossMajorConstraintsRefuseNamingBothRequirersAndPaths() {
        List<MinimalVersionSelector.Requirement> requirements = List.of(
                requirement("crm", "app>crm", "^2.0"),
                requirement("billing", "app>billing", "^3.0"));
        MinimalVersionSelector.Result result = MinimalVersionSelector.select("user", requirements, new PackVersion(3, 0, 0));
        assertInstanceOf(MinimalVersionSelector.Refused.class, result);
        String message = ((MinimalVersionSelector.Refused) result).message();
        assertTrue(message.contains("crm"), "must name crm, got: " + message);
        assertTrue(message.contains("billing"), "must name billing, got: " + message);
        assertTrue(message.contains("app -> crm"), "must name crm's path, got: " + message);
        assertTrue(message.contains("app -> billing"), "must name billing's path, got: " + message);
    }

    @Test
    void identicalExactConstraintsSucceed() {
        List<MinimalVersionSelector.Requirement> requirements = List.of(
                requirement("crm", "app>crm", "2.5.0"),
                requirement("billing", "app>billing", "2.5.0"));
        MinimalVersionSelector.Result result = MinimalVersionSelector.select("user", requirements, new PackVersion(2, 5, 0));
        assertInstanceOf(MinimalVersionSelector.Selected.class, result);
    }

    @Test
    void differingExactConstraintsRefuse() {
        List<MinimalVersionSelector.Requirement> requirements = List.of(
                requirement("crm", "app>crm", "2.5.0"),
                requirement("billing", "app>billing", "2.6.0"));
        MinimalVersionSelector.Result result = MinimalVersionSelector.select("user", requirements, new PackVersion(2, 5, 0));
        assertInstanceOf(MinimalVersionSelector.Refused.class, result);
        assertTrue(((MinimalVersionSelector.Refused) result).message().contains("conflicting exact"));
    }

    @Test
    void localVersionBelowTheComputedMinimumRefusesNamingNeededVersusPresent() {
        List<MinimalVersionSelector.Requirement> requirements = List.of(
                requirement("crm", "app>crm", "^2.5"));
        MinimalVersionSelector.Result result = MinimalVersionSelector.select("user", requirements, new PackVersion(2, 3, 0));
        assertInstanceOf(MinimalVersionSelector.Refused.class, result);
        String message = ((MinimalVersionSelector.Refused) result).message();
        assertTrue(message.contains("2.5.0"), "must name the needed floor, got: " + message);
        assertTrue(message.contains("2.3.0"), "must name the present local version, got: " + message);
        assertTrue(message.contains("npdev pack update"), "must tell the user the remedy, got: " + message);
    }
}
