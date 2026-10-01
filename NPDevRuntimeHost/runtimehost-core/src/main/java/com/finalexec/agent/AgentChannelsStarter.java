package com.finalexec.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

/**
 * AGENT-1 (A7.3): starts/stops the agent chat channels. Telegram starts iff the model enables
 * {@code agentAccess.channels.telegram} AND {@code NPDEV_TELEGRAM_BOT_TOKEN} is set -- otherwise
 * logs exactly which of the two is missing, once, rather than failing boot (a model shipped with
 * the channel enabled but no token configured yet is a normal, mid-setup state, not an error).
 */
@Component
public final class AgentChannelsStarter {

    private static final Logger LOG = Logger.getLogger(AgentChannelsStarter.class.getName());

    private final ModelHolder modelHolder;
    private final ObjectMapper mapper;
    private final AgentLinkService links;
    private final AgentApiExecutor executor;
    private final ObjectProvider<ExternalAiCapabilityContract> aiProvider;
    private final String telegramBotToken;
    private final String publicBaseUrl;
    private final String agentVendor;
    private final String agentModel;

    private volatile TelegramChannel telegramChannel;

    public AgentChannelsStarter(
            ModelHolder modelHolder,
            ObjectMapper mapper,
            AgentLinkService links,
            AgentApiExecutor executor,
            ObjectProvider<ExternalAiCapabilityContract> aiProvider,
            @Value("${NPDEV_TELEGRAM_BOT_TOKEN:}") String telegramBotToken,
            @Value("${NPDEV_PUBLIC_BASE_URL:}") String publicBaseUrl,
            @Value("${NPDEV_AGENT_VENDOR:gemini}") String agentVendor,
            @Value("${NPDEV_AGENT_MODEL:}") String agentModel
    ) {
        this.modelHolder = modelHolder;
        this.mapper = mapper;
        this.links = links;
        this.executor = executor;
        this.aiProvider = aiProvider;
        this.telegramBotToken = telegramBotToken;
        this.publicBaseUrl = publicBaseUrl;
        this.agentVendor = agentVendor == null || agentVendor.isBlank() ? "gemini" : agentVendor;
        this.agentModel = agentModel == null || agentModel.isBlank() ? null : agentModel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        var access = modelHolder.get().getAgentAccess();
        boolean telegramWanted = access != null && access.getChannels() != null
                && access.getChannels().getTelegram().isEnabled();
        boolean tokenPresent = telegramBotToken != null && !telegramBotToken.isBlank();

        if (telegramWanted && tokenPresent) {
            ExternalAiCapabilityContract ai = aiProvider.getIfAvailable();
            if (ai == null) {
                LOG.warning("agentAccess.channels.telegram is enabled but no external-AI provider is wired "
                        + "(set NPDEV_EXTERNALAI_PROVIDER=http); the Telegram channel will start but every "
                        + "chat answer will fail.");
            }
            AgentConversationService conversations = new AgentConversationService(
                    modelHolder::get, ai == null ? new DenyingAi() : ai, executor, links, mapper,
                    agentVendor, agentModel);
            TelegramChannel channel = new TelegramChannel(telegramBotToken, mapper, links, conversations, publicBaseUrl);
            this.telegramChannel = channel;
            channel.start();
        } else if (telegramWanted) {
            LOG.info("agentAccess.channels.telegram is enabled but NPDEV_TELEGRAM_BOT_TOKEN is not set -- "
                    + "Telegram channel not started.");
        }

        logAiKeyWarningIfNeeded();
    }

    private void logAiKeyWarningIfNeeded() {
        var access = modelHolder.get().getAgentAccess();
        if (access == null) {
            return;
        }
        ExternalAiCapabilityContract ai = aiProvider.getIfAvailable();
        boolean anyKeyPresent = ai != null && ai.configuredVendors().stream().anyMatch(ExternalAiVendorSummary::keyPresent);
        if (!anyKeyPresent) {
            LOG.warning("This app declares agentAccess but no external-AI vendor has a configured API key "
                    + "(secrets/agent-proxy.env) -- the MCP endpoint still works (the calling agent brings "
                    + "its own model), but every Telegram/WhatsApp chat answer will be an error.");
        }
    }

    public TelegramChannel telegramChannel() {
        return telegramChannel;
    }

    @PreDestroy
    public void stop() {
        TelegramChannel channel = telegramChannel;
        if (channel != null) {
            channel.stop();
        }
    }

    /** Fail-closed fallback when no ExternalAiCapabilityContract bean exists at all, so
     *  AgentConversationService.ai never needs a null check. */
    private static final class DenyingAi implements ExternalAiCapabilityContract {
        @Override
        public com.npdev.kernel.ports.ExternalAiVerdictRecord ingestVerdict(
                String missionId, String vendorId, String verdictJson) {
            throw new com.npdev.kernel.ports.ExternalAiEgressDeniedException(
                    "EGRESS_DENIED_NOT_CONFIGURED", "This app has no external-AI provider wired.");
        }
    }
}
