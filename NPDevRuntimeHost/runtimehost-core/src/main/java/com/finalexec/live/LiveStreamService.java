package com.finalexec.live;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * FORM P5-T5: the rules of {@code GET /api/live} that do not depend on Spring or on the generated context service,
 * so they are testable on their own. The controller supplies {@code canRead} -- the same permission gate the REST list
 * of that concept applies -- and a {@link LiveChangeHub.Sink} that writes to the response.
 *
 * <ul>
 *   <li>{@code concepts} is a comma list of concept names; blanks and repeats are ignored, a name that is not an
 *       identifier is refused with 400 before anything else runs (hostile input never reaches a lookup).</li>
 *   <li>At most {@link #MAX_CONCEPTS} distinct names per stream (400 above that).</li>
 *   <li>A name the caller may not read, or that does not exist, is dropped silently; if nothing is left the answer is
 *       403 and no stream is opened (so the client falls back to polling, which would also be refused).</li>
 *   <li>The per-user stream cap is the hub's; the one over it is a 429.</li>
 * </ul>
 */
public final class LiveStreamService {

    public static final int MAX_CONCEPTS = 10;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final LiveChangeHub hub;

    public LiveStreamService(LiveChangeHub hub) {
        this.hub = hub;
    }

    /** A refusal the controller turns into an HTTP status. */
    public static final class Refused extends RuntimeException {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public record Opened(LiveChangeHub.Subscription subscription, List<String> concepts) {
    }

    public Opened open(String tenant, String user, String conceptsParam, Predicate<String> canRead, LiveChangeHub.Sink sink) {
        Set<String> requested = parse(conceptsParam);
        List<String> readable = new ArrayList<>();
        for (String name : requested) {
            boolean allowed;
            try {
                allowed = canRead.test(name);
            } catch (RuntimeException unreadable) {
                allowed = false;
            }
            if (allowed) {
                readable.add(name);
            }
        }
        if (readable.isEmpty()) {
            throw new Refused(403, "None of the requested concepts can be read.");
        }
        try {
            return new Opened(hub.subscribe(tenant, user, readable, sink), List.copyOf(readable));
        } catch (LiveChangeHub.TooManyStreamsException tooMany) {
            throw new Refused(429, tooMany.getMessage());
        }
    }

    static Set<String> parse(String conceptsParam) {
        Set<String> names = new LinkedHashSet<>();
        if (conceptsParam != null) {
            for (String part : conceptsParam.split(",", -1)) {
                String name = part.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!NAME.matcher(name).matches()) {
                    throw new Refused(400, "Invalid concept name in 'concepts'.");
                }
                names.add(name);
                if (names.size() > MAX_CONCEPTS) {
                    throw new Refused(400, "At most " + MAX_CONCEPTS + " concepts per stream.");
                }
            }
        }
        if (names.isEmpty()) {
            throw new Refused(400, "Query parameter 'concepts' (comma separated concept names) is required.");
        }
        return names;
    }
}
