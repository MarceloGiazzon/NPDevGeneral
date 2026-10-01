package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * AGENT-1 (A6.4): emits {@code static/agent-link.html} -- the logged-in self-service page where a
 * user connects Telegram/WhatsApp (code + deep link) and creates an MCP token for Claude Code/
 * Desktop. Mirrors {@link ChangePasswordPageEmitter} exactly: same jwt-mode gating (both channels
 * need it -- A1.11), same fixed-content-so-deterministic-generation-is-unaffected shape, same
 * token-from-localStorage reuse of shell.js's TOKEN_KEYS. Only emitted when the model declares an
 * {@code agentAccess} block at all -- unlike change-password, there is nothing useful to show a
 * jwt-mode app with no agent access configured.
 */
public final class AgentLinkPageEmitter extends AbstractEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/static/agent-link.html";

    public AgentLinkPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model, boolean jwtMode) {
        if (!jwtMode || model == null || model.getAgentAccess() == null) {
            return;
        }
        String appName = model.getNamespace() == null || model.getNamespace().isBlank()
                ? "NPDev Generated App" : model.getNamespace().trim();
        writer.writeRelative(RELATIVE_PATH, render(Map.of("appName", appName)));
    }

    private String render(Map<String, Object> view) {
        return templates.render("agent-link-page.html.mustache", view);
    }
}
