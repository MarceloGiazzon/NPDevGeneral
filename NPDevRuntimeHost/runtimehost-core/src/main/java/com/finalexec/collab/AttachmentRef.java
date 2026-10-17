package com.finalexec.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * PROC-6: the {@code file_json} of an ATTACHMENT row -- where the bytes live in the file store plus what
 * the thread shows. The store key is never sent to a client: a download goes through the record's own
 * read gate ({@code RecordCommentService#openAttachment}), not through {@code /api/files?key=}.
 */
public record AttachmentRef(String storeId, String key, String contentType, long size, String originalName) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("storeId", storeId);
        node.put("key", key);
        node.put("contentType", contentType);
        node.put("size", size);
        node.put("originalName", originalName);
        return node.toString();
    }

    public static Optional<AttachmentRef> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.hasNonNull("storeId") || !node.hasNonNull("key")) {
                return Optional.empty();
            }
            return Optional.of(new AttachmentRef(node.get("storeId").asText(), node.get("key").asText(),
                    node.path("contentType").asText("application/octet-stream"), node.path("size").asLong(0),
                    node.path("originalName").asText("file")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
