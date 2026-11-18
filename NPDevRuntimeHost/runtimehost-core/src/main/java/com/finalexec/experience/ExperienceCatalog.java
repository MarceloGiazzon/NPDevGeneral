package com.finalexec.experience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * FORM P5-T2: the resolved {@code experiences[]} / {@code screens[]} of the running app (the generated
 * {@code npdev/experiences.json}) and every decision the runtime takes from them. Pure, immutable and free of
 * Spring so it can be unit tested without a context; {@code com.finalexec.api.ExperienceController} is the thin HTTP
 * layer over it.
 *
 * <p><b>Who lands where.</b> A caller's experience is the FIRST declared experience whose {@code for} contains one
 * of the caller's roles (role names compare case-insensitively, like {@code ExecutionContext}); none means the
 * caller belongs in the generated back office.
 *
 * <p><b>Which flows a browser may start.</b> {@link #authorizeFlowStart}: only a flow that exists, is exposed as
 * {@code ui} or {@code api} (never {@code internal}), and is the {@code flow} of an action on a screen that belongs
 * to an experience whose {@code for} intersects the caller's roles. A screen "belongs" to an experience when it is
 * reachable from it: its {@code home}, a {@code navigation} entry, an action's {@code then}, or a widget's
 * {@code screen}, transitively. This is a doorway, not the permission: the flow's own roles and the concepts'
 * access rules still decide every step, because the flow runs as the caller.
 */
public final class ExperienceCatalog {

    public static final String RESOURCE = "npdev/experiences.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExperienceCatalog EMPTY = new ExperienceCatalog(List.of(), List.of());

    /** What the runtime knows about a flow, supplied by the caller (the compiled model). */
    public record FlowFacts(String name, String expose) {
    }

    private final List<JsonNode> experiences;
    private final List<JsonNode> screens;

    private ExperienceCatalog(List<JsonNode> experiences, List<JsonNode> screens) {
        this.experiences = experiences;
        this.screens = screens;
    }

    public static ExperienceCatalog empty() {
        return EMPTY;
    }

    public static ExperienceCatalog fromJson(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            return fromTree(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new UncheckedIOException("experiences.json is not valid JSON", e);
        }
    }

    /** Reads {@value #RESOURCE} from the classpath once; an app generated without experiences has no such file. */
    public static ExperienceCatalog fromClasspath() {
        try (InputStream in = ExperienceCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return EMPTY;
            }
            return fromTree(MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + RESOURCE, e);
        }
    }

    private static ExperienceCatalog fromTree(JsonNode root) {
        if (root == null || !root.isObject()) {
            return EMPTY;
        }
        return new ExperienceCatalog(list(root.get("experiences")), list(root.get("screens")));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return List.copyOf(out);
    }

    public boolean isEmpty() {
        return experiences.isEmpty();
    }

    /** True when an experience with exactly this name (the URL segment) is declared. */
    public boolean hasExperience(String name) {
        if (name == null) {
            return false;
        }
        return experiences.stream().anyMatch(experience -> name.equals(experience.path("name").asText(null)));
    }

    /** The resolved experience with exactly this name (the URL segment), if declared. */
    public Optional<JsonNode> experienceNamed(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return experiences.stream().filter(experience -> name.equals(experience.path("name").asText(null))).findFirst();
    }

    /** The first declared experience whose {@code for} intersects {@code roles}. */
    public Optional<JsonNode> experienceFor(Collection<String> roles) {
        Set<String> wanted = upper(roles);
        return experiences.stream().filter(experience -> matches(experience, wanted)).findFirst();
    }

    /**
     * The body of {@code GET /api/x/me}: the caller's experience (or {@code null}), the resolved screens that
     * experience reaches (declaration order), and the caller's id (actor id, {@code $user.id}) and roles.
     */
    public Map<String, Object> me(Collection<String> roles, String actorId) {
        Optional<JsonNode> experience = experienceFor(roles);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("experience", experience.orElse(null));
        List<JsonNode> reached = experience.map(this::reachableScreens).orElse(List.of());
        body.put("screens", reached);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", actorId);
        user.put("roles", new ArrayList<>(new TreeSet<>(upper(roles))));
        body.put("user", user);
        return body;
    }

    /**
     * The canonical name of {@code flow} when a caller holding {@code roles} may start it from a browser, else
     * empty (the caller answers 404, indistinguishable from a missing flow).
     *
     * @param lookup the flow's facts from the compiled model, or empty when no such flow exists
     */
    public Optional<String> authorizeFlowStart(
            Collection<String> roles,
            String flow,
            Function<String, Optional<FlowFacts>> lookup
    ) {
        if (flow == null || flow.isBlank()) {
            return Optional.empty();
        }
        Optional<FlowFacts> facts = lookup.apply(flow);
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        String expose = facts.get().expose();
        boolean browserStartable = expose == null || expose.isBlank() || "ui".equals(expose) || "api".equals(expose);
        if (!browserStartable) {
            return Optional.empty();
        }
        Set<String> wanted = upper(roles);
        for (JsonNode experience : experiences) {
            if (!matches(experience, wanted)) {
                continue;
            }
            for (JsonNode screen : reachableScreens(experience)) {
                for (JsonNode action : iterable(screen.get("actions"))) {
                    if (facts.get().name() != null && facts.get().name().equalsIgnoreCase(action.path("flow").asText(""))) {
                        return Optional.of(facts.get().name());
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ reachability

    /** Screens reachable from {@code experience} (home, navigation, action {@code then}, widget {@code screen}). */
    private List<JsonNode> reachableScreens(JsonNode experience) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode screen : screens) {
            byName.putIfAbsent(lower(screen.path("name").asText("")), screen);
        }
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        enqueue(experience.path("home").asText(""), reached, pending);
        for (JsonNode entry : iterable(experience.get("navigation"))) {
            enqueue(entry.path("screen").asText(""), reached, pending);
        }
        while (!pending.isEmpty()) {
            JsonNode screen = byName.get(pending.poll());
            if (screen == null) {
                continue;
            }
            for (JsonNode action : iterable(screen.get("actions"))) {
                enqueue(action.path("then").asText(""), reached, pending);
            }
            for (JsonNode widget : iterable(screen.get("widgets"))) {
                enqueue(widget.path("screen").asText(""), reached, pending);
            }
            // A row tap on a list/inbox/board opens the FIRST declared detail screen of the same concept (the client's
            // detailFor rule, kept identical on purpose: a screen the client may open must be one the server sent, and its
            // actions must be authorisable). Nothing else makes a detail screen reachable.
            String kind = screen.path("kind").asText("");
            String concept = screen.path("concept").asText("");
            if (!concept.isEmpty() && ("list".equals(kind) || "inbox".equals(kind) || "board".equals(kind))) {
                for (JsonNode candidate : screens) {
                    if ("detail".equals(candidate.path("kind").asText("")) && concept.equals(candidate.path("concept").asText(""))) {
                        enqueue(candidate.path("name").asText(""), reached, pending);
                        break;
                    }
                }
            }
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode screen : screens) {
            if (reached.contains(lower(screen.path("name").asText("")))) {
                out.add(screen);
            }
        }
        return out;
    }

    private static void enqueue(String screenName, Set<String> reached, Deque<String> pending) {
        String key = lower(screenName);
        if (!key.isEmpty() && reached.add(key)) {
            pending.add(key);
        }
    }

    private static boolean matches(JsonNode experience, Set<String> wantedUpperRoles) {
        for (JsonNode role : iterable(experience.get("for"))) {
            if (role.isTextual() && wantedUpperRoles.contains(role.asText().trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> upper(Collection<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    out.add(role.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return array != null && array.isArray() ? array : List.of();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
