package com.npdev.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CapabilityContract {
    private final String name;
    private final List<CapabilityOperationContract> operations;
    private final Map<String, CapabilityOperationContract> operationsByLowerName;

    public CapabilityContract(String name, List<CapabilityOperationContract> operations) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        this.name = name;
        this.operations = operations == null ? List.of() : List.copyOf(new ArrayList<>(operations));
        this.operationsByLowerName = this.operations.stream()
                .collect(Collectors.toMap(
                        op -> normalize(op.getName()),
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalArgumentException("Duplicate operation in capability contract " + name
                                    + ": " + a.getName());
                        }
                ));
    }

    public String getName() {
        return name;
    }

    public List<CapabilityOperationContract> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public boolean supportsOperation(String operation) {
        return operationsByLowerName.containsKey(normalize(operation));
    }

    public CapabilityOperationContract resolveOperation(String operation) {
        CapabilityOperationContract contract = operationsByLowerName.get(normalize(operation));
        if (contract == null) {
            throw new IllegalStateException("Operation " + operation + " is not declared in capability contract " + name);
        }
        return contract;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

