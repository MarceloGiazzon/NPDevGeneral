package com.npdev.generator.api;

import com.npdev.dsl.v1.compiled.CompiledAgentAccess;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;

/**
 * AGENT-1: refuse to generate an app whose {@code agentAccess.channels.telegram}/{@code whatsapp} is
 * enabled without the prerequisites those channels need to actually work: {@code auth.mode=jwt} (the
 * channel mints a short-lived JWT for the linked user) and the identity pack's
 * {@code identity::ExternalIdentity} concept (where the Telegram/WhatsApp link is stored). MCP needs
 * neither -- it authenticates with the caller's own bearer token in every auth mode.
 */
final class AgentAccessChannelCheck {

    private AgentAccessChannelCheck() {
    }

    static void verify(CompiledModel model, SettingResolver settingResolver) {
        if (model == null || settingResolver == null) {
            return;
        }
        CompiledAgentAccess agentAccess = model.getAgentAccess();
        if (agentAccess == null || agentAccess.getChannels() == null) {
            return;
        }
        boolean telegram = agentAccess.getChannels().getTelegram().isEnabled();
        boolean whatsapp = agentAccess.getChannels().getWhatsapp().isEnabled();
        if (!telegram && !whatsapp) {
            return;
        }
        String authMode = settingResolver.value(NpdevSettings.AUTH_MODE, SettingTarget.app());
        boolean hasIdentityPack = model.findConcept("identity::ExternalIdentity").isPresent();
        if (!"jwt".equals(authMode) || !hasIdentityPack) {
            String channelName = telegram ? "telegram" : "whatsapp";
            throw new IllegalStateException(
                    "agentAccess.channels." + channelName + " needs auth.mode=jwt and the identity pack "
                            + "(account linking stores the Telegram/WhatsApp id in identity::ExternalIdentity). "
                            + "Add the identity pack and set defaults.auth.mode to jwt in config.json, or disable "
                            + "the channel.");
        }
    }
}
