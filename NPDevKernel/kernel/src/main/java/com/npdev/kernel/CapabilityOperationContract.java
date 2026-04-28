package com.npdev.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityOperationContract {
    private final String name;
    private final List<String> input;
    private final List<String> output;

    public CapabilityOperationContract(String name, List<String> input, List<String> output) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        this.name = name;
        this.input = input == null ? List.of() : new ArrayList<>(input);
        this.output = output == null ? List.of() : new ArrayList<>(output);
    }

    public String getName() {
        return name;
    }

    public List<String> getInput() {
        return Collections.unmodifiableList(input);
    }

    public List<String> getOutput() {
        return Collections.unmodifiableList(output);
    }
}

