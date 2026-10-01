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

    private static final int MAX_TURNS_KEPT = 24;
    private static final int MAX_TOOL_ROUNDS = 6;
    private static final Duration IDLE_RESET = Duration.ofMinutes(30);
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final Map<String, List<ExternalAiChatTurn>> history = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

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
