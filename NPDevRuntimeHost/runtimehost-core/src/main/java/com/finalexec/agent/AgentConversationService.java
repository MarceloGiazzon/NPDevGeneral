package com.finalexec.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiChatTurn;
import com.npdev.kernel.ports.ExternalAiToolCall;
import com.npdev.kernel.ports.ExternalAiToolChatRequest;
import com.npdev.kernel.ports.ExternalAiToolChatResult;
import com.npdev.kernel.ports.ExternalAiToolSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * AGENT-1 (A7.1): the channel-agnostic chat loop -- Telegram ({@link TelegramChannel}, this phase)
 * and WhatsApp (A10) both call {@link #handleText}/{@link #handleConfirmation}. Per-user memory
 * with idle reset, a bounded tool-calling round trip, and the confirm-before-write pause required
 * by {@code AgentAccessExposureAst#getConfirmWrites()} -- including placeholder tool results for
 * every OTHER call in the same assistant turn, since OpenAI/Anthropic reject a request that leaves
 * any {@code tool_use}/{@code tool_call} without a matching result.
 */
public final class AgentConversationService {

    /** Who is talking: resolved from the link table before this service is called. */
    public record Speaker(String channel, String channelUserId, String tenantId, String username, Set<String> roles) {
        String key() {
            return channel + ":" + channelUserId;
        }
    }

    /** What the channel must send back. {@code confirmId != null} -> render Confirm/Cancel buttons
     *  carrying it. */
    public record Reply(String text, String confirmId) {
        static Reply text(String text) {
            return new Reply(text, null);
        }
    }

    private record Pending(String id, Speaker speaker, AgentToolCatalog.AgentTool tool, Map<String, Object> args,
            ExternalAiToolCall call, Instant createdAt) {
    }

    /** P8: a photo's assembled row, waiting for Confirm; not part of the LLM conversation. */
    private record PendingPhoto(Speaker speaker, String route, String conceptLabel, Map<String, Object> draft,
            Instant createdAt) {
    }

    private static final String PHOTO_PREFIX = "ph-";

    private static final int MAX_TURNS_KEPT = 24;
    private static final int MAX_TOOL_ROUNDS = 6;
    private static final Duration IDLE_RESET = Duration.ofMinutes(30);
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final Map<String, List<ExternalAiChatTurn>> history = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, PendingPhoto> pendingPhotos = new ConcurrentHashMap<>();

    private final Supplier<com.npdev.dsl.v1.compiled.CompiledModel> model;
    private final ExternalAiCapabilityContract ai;
    private final AgentApiExecutor executor;
    private final AgentLinkService links;
    private final ObjectMapper mapper;
    private final String vendorId;
    private final String modelId;

    public AgentConversationService(Supplier<com.npdev.dsl.v1.compiled.CompiledModel> model,
            ExternalAiCapabilityContract ai, AgentApiExecutor executor, AgentLinkService links, ObjectMapper mapper,
            String vendorId, String modelId) {
        this.model = model;
        this.ai = ai;
        this.executor = executor;
        this.links = links;
        this.mapper = mapper;
        this.vendorId = vendorId;
        this.modelId = modelId;
    }

    public Reply handleText(Speaker speaker, String text) {
        if (text.equalsIgnoreCase("/reset")) {
            history.remove(speaker.key());
            return Reply.text("Conversation cleared.");
        }
        resetIfIdle(speaker);
        List<ExternalAiChatTurn> turns = history.computeIfAbsent(speaker.key(), k -> new ArrayList<>());
        synchronized (turns) {
            turns.add(ExternalAiChatTurn.user(text));
            return runLoop(speaker, turns);
        }
    }

    /** Called when the user presses Confirm (approved=true) or Cancel on a pending write. */
    public Reply handleConfirmation(Speaker speaker, String confirmId, boolean approved) {
        if (confirmId.startsWith(PHOTO_PREFIX)) {
            return confirmPhoto(speaker, confirmId, approved);
        }
        Pending action = pending.remove(confirmId);
        if (action == null || !action.speaker().key().equals(speaker.key())
                || action.createdAt().plus(PENDING_TTL).isBefore(Instant.now())) {
            return Reply.text("That confirmation has expired. Please ask again.");
        }
        List<ExternalAiChatTurn> turns = history.computeIfAbsent(speaker.key(), k -> new ArrayList<>());
        synchronized (turns) {
            String resultJson = approved
                    ? executor.execute(action.tool(), action.args(), credentialsFor(speaker)).toToolResultJson(mapper)
                    : "{\"status\":0,\"body\":\"The user cancelled this action. Do not retry it unless they ask again.\"}";
            turns.add(ExternalAiChatTurn.toolResult(action.call().id(), action.call().name(), resultJson));
            return runLoop(speaker, turns);
        }
    }

    /** P8: the image field's size limit (bytes), so a channel can pick a photo size that fits; null =
     *  no limit declared or photo intake off. */
    public Long photoMaxBytes() {
        return AgentPhotoIntake.target(model.get())
                .flatMap(t -> t.concept().getFields().stream()
                        .filter(f -> f.getName().equalsIgnoreCase(t.intake().getImageField())).findFirst())
                .map(f -> f.getFile() == null ? null : f.getFile().maxSizeBytes())
                .orElse(null);
    }

    /**
     * P8 (G5): a photo sent to the bot. Uploads it into the intake concept's image field, runs the
     * intake procedure over the draft (AI identification), merges caption + defaults, and asks the user
     * to confirm. Nothing is created until Confirm; a cancelled photo's upload is left to the orphan
     * sweeper. Every call goes through the app's REST API as the linked user.
     */
    public Reply handlePhoto(Speaker speaker, byte[] bytes, String contentType, String fileName, String caption) {
        AgentPhotoIntake.Target target = AgentPhotoIntake.target(model.get()).orElse(null);
        if (target == null) {
            return Reply.text("I can't do anything with photos in this app -- please send text.");
        }
        AgentApiExecutor.Credentials credentials = credentialsFor(speaker);
        AgentApiExecutor.Outcome upload = executor.uploadFile(target.concept().getName(),
                target.intake().getImageField(), bytes, contentType, fileName, credentials);
        Map<String, Object> handle = readMap(upload.body());
        if (!upload.ok() || handle == null) {
            return Reply.text("I couldn't store that photo (" + upload.status() + "): " + errorText(upload.body()));
        }
        Map<String, Object> state = null;
        String procedureNote = "";
        if (target.intake().getProcedure() != null && target.aggregate() != null) {
            AgentApiExecutor.Outcome invoked = executor.postJson(
                    "/api/runtime/aggregate/" + enc(target.aggregate()) + "/invoke/" + enc(target.intake().getProcedure()),
                    AgentPhotoIntake.procedureInput(target, handle, caption), credentials, false);
            state = invoked.ok() ? readMap(invoked.body()) : null;
            if (state == null) {
                procedureNote = "\n(" + target.intake().getProcedure() + " did not answer: " + errorText(invoked.body()) + ")";
            }
        }
        Map<String, Object> draft = AgentPhotoIntake.draft(target, speaker.username(), handle, caption, state);
        String label = target.concept().getUi() != null && target.concept().getUi().getLabel() != null
                ? target.concept().getUi().getLabel() : target.concept().getName();
        List<String> missing = AgentPhotoIntake.missingRequired(target, draft);
        if (!missing.isEmpty()) {
            String hint = target.intake().getCaptionField() != null && missing.contains(target.intake().getCaptionField())
                    ? " Send the photo again with a caption -- it becomes the " + target.intake().getCaptionField() + "." : "";
            return Reply.text("I can't make a " + label + " from that photo yet: missing " + missing + "." + hint + procedureNote);
        }
        String id = PHOTO_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        pendingPhotos.put(id, new PendingPhoto(speaker, target.route(), label, draft, Instant.now()));
        StringBuilder text = new StringBuilder("New " + label + " from your photo:\n");
        draft.forEach((key, value) -> {
            if (!key.equalsIgnoreCase(target.intake().getImageField())) {
                text.append("- ").append(key).append(": ").append(value).append('\n');
            }
        });
        return new Reply(text.append("Save it?").append(procedureNote).toString(), id);
    }

    private Reply confirmPhoto(Speaker speaker, String confirmId, boolean approved) {
        PendingPhoto photo = pendingPhotos.remove(confirmId);
        if (photo == null || !photo.speaker().key().equals(speaker.key())
                || photo.createdAt().plus(PENDING_TTL).isBefore(Instant.now())) {
            return Reply.text("That photo has expired. Please send it again.");
        }
        if (!approved) {
            return Reply.text("Discarded.");
        }
        AgentApiExecutor.Outcome created = executor.postJson(
                "/api/concepts/" + enc(photo.route()), photo.draft(), credentialsFor(speaker), true);
        if (!created.ok()) {
            return Reply.text(created.status() == 403
                    ? "You are not allowed to add a " + photo.conceptLabel() + "."
                    : "I couldn't save it (" + created.status() + "): " + errorText(created.body()));
        }
        return Reply.text("Saved -- the " + photo.conceptLabel() + " is in the app.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String body) {
        try {
            Object parsed = mapper.readValue(body == null ? "" : body, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (Exception notJson) {
            return null;
        }
    }

    private String errorText(String body) {
        Map<String, Object> map = readMap(body);
        Object message = map == null ? null : map.getOrDefault("message", map.get("error"));
        String text = message != null ? String.valueOf(message) : body == null ? "" : body;
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Reply runLoop(Speaker speaker, List<ExternalAiChatTurn> turns) {
        List<AgentToolCatalog.AgentTool> tools = AgentToolCatalog.toolsFor(model.get(), speaker.roles());
        List<ExternalAiToolSpec> specs = tools.stream()
                .map(t -> new ExternalAiToolSpec(t.name(), t.description(), t.inputSchema())).toList();
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ExternalAiToolChatResult result;
            try {
                result = ai.chatWithTools(new ExternalAiToolChatRequest(
                        vendorId, modelId, systemPrompt(speaker), specs, List.copyOf(turns)));
            } catch (RuntimeException failed) {
                return Reply.text("Sorry, the assistant is unavailable right now (" + failed.getClass().getSimpleName() + ").");
            }
            turns.add(result.assistantTurn());
            trim(turns);
            if (!result.wantsTools()) {
                String text = result.assistantTurn().text();
                return Reply.text(text == null || text.isBlank() ? "(no answer)" : text);
            }
            List<ExternalAiToolCall> calls = result.assistantTurn().toolCalls();
            for (int c = 0; c < calls.size(); c++) {
                ExternalAiToolCall call = calls.get(c);
                AgentToolCatalog.AgentTool tool = AgentToolCatalog.find(tools, call.name()).orElse(null);
                if (tool == null) {
                    turns.add(ExternalAiChatTurn.toolResult(call.id(), call.name(),
                            "{\"status\":404,\"body\":\"No such tool for this user.\"}"));
                    continue;
                }
                if (tool.write() && tool.confirmWrites()) {
                    // Stop here and ask the human. EVERY other call of this assistant turn must still
                    // get a result, or OpenAI/Anthropic reject the next request ("tool_call without
                    // response"). The pending call's own result is added by handleConfirmation; the
                    // adapter merges consecutive tool turns into one vendor message (A2.3).
                    for (int rest = c + 1; rest < calls.size(); rest++) {
                        turns.add(ExternalAiChatTurn.toolResult(calls.get(rest).id(), calls.get(rest).name(),
                                "{\"status\":0,\"body\":\"Not run: waiting for the user to confirm an earlier action. "
                                        + "Issue it again afterwards if still needed.\"}"));
                    }
                    String id = "p-" + UUID.randomUUID().toString().substring(0, 8);
                    pending.put(id, new Pending(id, speaker, tool, call.arguments(), call, Instant.now()));
                    return new Reply(describe(tool, call.arguments()), id);
                }
                String resultJson = executor.execute(tool, call.arguments(), credentialsFor(speaker)).toToolResultJson(mapper);
                turns.add(ExternalAiChatTurn.toolResult(call.id(), call.name(), resultJson));
            }
        }
        return Reply.text("I could not finish that in a few steps. Could you say it more simply?");
    }

    private AgentApiExecutor.Credentials credentialsFor(Speaker speaker) {
        String token = links.mintShortLivedToken(speaker.tenantId(), speaker.username()).orElse(null);
        if (token == null) {
            // No signing key configured -- the loopback call will 401, which the LLM then explains
            // to the user via the normal tool-error path. Never silently send unauthenticated.
            return new AgentApiExecutor.Credentials(null, null, speaker.channel());
        }
        return new AgentApiExecutor.Credentials("Authorization", "Bearer " + token, speaker.channel());
    }

    private String systemPrompt(Speaker speaker) {
        var access = model.get().getAgentAccess();
        String assistantName = access != null && access.getAssistant() != null && access.getAssistant().getName() != null
                ? access.getAssistant().getName() : model.get().getNamespace() + " assistant";
        String instructions = access != null && access.getAssistant() != null && access.getAssistant().getInstructions() != null
                ? access.getAssistant().getInstructions() : "";
        return """
                You are %s, the assistant of the business application "%s".
                You are talking to the user "%s" (roles: %s) through %s.
                Rules you must always follow:
                - Only state facts you got from a tool result in this conversation. Never invent records, ids, prices or stock.
                - To find a record's id, list or search first; never guess an id.
                - If a tool returns status 403, tell the user they are not allowed to do that. Do not retry.
                - If a tool returns an error, explain it in plain words and suggest what to change.
                - Keep answers short; this is a chat app.
                %s
                """.formatted(assistantName, model.get().getNamespace(), speaker.username(), speaker.roles(),
                speaker.channel(), instructions);
    }

    private String describe(AgentToolCatalog.AgentTool tool, Map<String, Object> args) {
        try {
            return "I am about to: " + tool.title() + "\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(args)
                    + "\nConfirm?";
        } catch (Exception e) {
            return "I am about to: " + tool.title() + ". Confirm?";
        }
    }

    private void resetIfIdle(Speaker speaker) {
        Instant now = Instant.now();
        Instant last = lastSeen.put(speaker.key(), now);
        if (last != null && last.plus(IDLE_RESET).isBefore(now)) {
            history.remove(speaker.key());
        }
    }

    /** Keep the newest turns, but never cut between an assistant tool call and its tool result. */
    private static void trim(List<ExternalAiChatTurn> turns) {
        while (turns.size() > MAX_TURNS_KEPT) {
            turns.remove(0);
            while (!turns.isEmpty() && !"user".equals(turns.get(0).role())) {
                turns.remove(0);
            }
        }
    }
}
