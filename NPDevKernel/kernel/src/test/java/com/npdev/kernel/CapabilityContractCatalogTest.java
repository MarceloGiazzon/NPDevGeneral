package com.npdev.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityContractCatalogTest {

    @Test
    void builtInCatalogContainsExpectedContracts() {
        CapabilityContractCatalog catalog = CapabilityContractCatalog.withBuiltIns();

        CapabilityContract persistence = catalog.resolve("PersistenceCapability");
        assertEquals("PersistenceCapability", persistence.getName());
        assertTrue(persistence.supportsOperation("save"));
        assertTrue(persistence.supportsOperation("unique"));

        CapabilityContract messaging = catalog.resolve("MessagingCapability");
        assertTrue(messaging.supportsOperation("publish"));

        CapabilityContract externalAi = catalog.resolve("ExternalAiCapability");
        assertTrue(externalAi.supportsOperation("submitPack"));
        assertTrue(externalAi.supportsOperation("ingestVerdict"));
    }

    @Test
    void customContractCanBeRegistered() {
        CapabilityContractCatalog catalog = CapabilityContractCatalog.withBuiltIns();
        catalog.register(new CapabilityContract(
                "CustomCapability",
                List.of(new CapabilityOperationContract("doWork", List.of("input"), List.of("output")))
        ));

        CapabilityContract contract = catalog.resolve("CustomCapability");
        assertTrue(contract.supportsOperation("doWork"));
    }
}

