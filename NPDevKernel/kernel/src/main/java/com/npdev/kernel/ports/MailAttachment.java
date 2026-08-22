package com.npdev.kernel.ports;

import java.util.Objects;

/**
 * R6.3 (RUN-18): one email attachment -- filename, MIME content type, and raw bytes. Constructed by
 * {@link MailPayload} from a flow-state map carrying {@code contentBase64} (the JSON-safe wire
 * encoding a flow's state can hold -- a flow's capabilityCall args are plain value refs, never raw
 * bytes), typically the {@code documentRender} capability's own output map
 * ({@code contentBase64}/{@code contentType}/{@code filename}), passed straight through as a single
 * attachment ref.
 */
public record MailAttachment(String filename, String contentType, byte[] contentBytes) {
    public MailAttachment {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("mail attachment filename must be non-blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("mail attachment contentType must be non-blank");
        }
        Objects.requireNonNull(contentBytes, "contentBytes");
    }
}
