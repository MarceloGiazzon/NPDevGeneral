package com.npdev.kernel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CapabilityContractCatalog {
    private final Map<String, CapabilityContract> contractsByLowerName = new LinkedHashMap<>();

    public CapabilityContractCatalog register(CapabilityContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("contract must be non-null");
        }
        contractsByLowerName.put(normalize(contract.getName()), contract);
        return this;
    }

    public Optional<CapabilityContract> find(String contractName) {
        return Optional.ofNullable(contractsByLowerName.get(normalize(contractName)));
    }

    public CapabilityContract resolve(String contractName) {
        return find(contractName)
                .orElseThrow(() -> new IllegalStateException("Capability contract not found: " + contractName));
    }

    public boolean has(String contractName) {
        return contractsByLowerName.containsKey(normalize(contractName));
    }

    public static CapabilityContractCatalog withBuiltIns() {
        CapabilityContractCatalog catalog = new CapabilityContractCatalog();
        catalog.register(new CapabilityContract(
                "PersistenceCapability",
                List.of(
                        new CapabilityOperationContract("save", List.of("entity"), List.of("entity")),
                        new CapabilityOperationContract("delete", List.of("concept", "id"), List.of("status")),
                        new CapabilityOperationContract("query", List.of("concept", "criteria"), List.of("items")),
                        new CapabilityOperationContract("exists", List.of("concept", "field", "value"), List.of("result")),
                        new CapabilityOperationContract("unique", List.of("concept", "field", "value"), List.of("result")),
                        new CapabilityOperationContract("findById", List.of("concept", "id"), List.of("entity"))
                )
        ));
        catalog.register(new CapabilityContract(
                "MessagingCapability",
                List.of(
                        new CapabilityOperationContract("publish", List.of("message"), List.of("ack")),
                        new CapabilityOperationContract("subscribe", List.of("topic", "handler"), List.of("subscriptionRef")),
                        new CapabilityOperationContract("unsubscribe", List.of("subscriptionRef"), List.of("ack"))
                )
        ));
        catalog.register(new CapabilityContract(
                "EmailCapability",
                List.of(new CapabilityOperationContract("send", List.of("email"), List.of("status")))
        ));
        catalog.register(new CapabilityContract(
                "FiscalCapability",
                List.of(
                        new CapabilityOperationContract("generateXml", List.of("document"), List.of("xml")),
                        new CapabilityOperationContract("sign", List.of("xml"), List.of("signed")),
                        new CapabilityOperationContract("transmit", List.of("signed"), List.of("receipt")),
                        new CapabilityOperationContract("queryStatus", List.of("receiptRef"), List.of("status"))
                )
        ));
        catalog.register(new CapabilityContract(
                "SignatureCapability",
                List.of(
                        new CapabilityOperationContract("sign", List.of("document"), List.of("signedDocument")),
                        new CapabilityOperationContract("verify", List.of("signedDocument"), List.of("verificationResult"))
                )
        ));
        return catalog;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

