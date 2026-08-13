package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared JSON-report serialize/write helpers for the {@code pack *} CLI Main classes -- same
 *  convention {@link ModelValidatorMain} already uses privately, factored out since four Main
 *  classes now need it instead of one. */
final class ReportIo {

    private ReportIo() {
    }

    static String serialize(ObjectMapper mapper, ObjectNode report) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (IOException serializationError) {
            throw new IllegalStateException("failed to serialize report", serializationError);
        }
    }

    static void write(String outArg, String json) {
        try {
            Path outPath = Path.of(outArg);
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.writeString(outPath, json + System.lineSeparator());
        } catch (IOException writeError) {
            System.err.println("failed to write report to " + outArg + ": " + writeError.getMessage());
            System.exit(70);
        }
    }
}
