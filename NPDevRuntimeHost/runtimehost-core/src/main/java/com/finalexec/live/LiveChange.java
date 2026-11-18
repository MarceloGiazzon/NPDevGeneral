package com.finalexec.live;

/**
 * FORM P5-T5: what a live stream pushes -- "this row changed", never the row. The client refetches through the normal
 * REST API, so every access rule applies to what it then reads. {@code version} is the row version after the write
 * (null when the write did not produce one: a delete, a rollup recomputation, an unversioned table).
 */
public record LiveChange(String concept, String id, Long version) {

    /** The {@code data:} line of the {@code changed} event: {@code {"concept":"Ride","id":"...","version":3}}. */
    public String toJson() {
        StringBuilder out = new StringBuilder(64);
        out.append("{\"concept\":");
        quote(out, concept);
        out.append(",\"id\":");
        quote(out, id);
        out.append(",\"version\":").append(version == null ? "null" : version.toString()).append('}');
        return out.toString();
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
