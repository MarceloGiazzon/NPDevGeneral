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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * AGENT-1 (A7.3): starts/stops the agent chat channels. Telegram starts iff the model enables
 * {@code agentAccess.channels.telegram} AND {@code NPDEV_TELEGRAM_BOT_TOKEN} is set -- otherwise
 * logs exactly which of the two is missing, once, rather than failing boot (a model shipped with
 * the channel enabled but no token configured yet is a normal, mid-setup state, not an error).
 * WhatsApp (A10) follows the same rule with its four {@code NPDEV_WHATSAPP_*} secrets; it has no
 * thread of its own -- {@code AgentWhatsAppWebhookController} hands it each delivery.
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
    private final String whatsappPhoneNumberId;
    private final String whatsappAccessToken;
    private final String whatsappAppSecret;
    private final String whatsappVerifyToken;

    private volatile TelegramChannel telegramChannel;
    private volatile WhatsAppChannel whatsappChannel;
    private AgentConversationService conversations;

    public AgentChannelsStarter(
            ModelHolder modelHolder,
            ObjectMapper mapper,
            AgentLinkService links,
            AgentApiExecutor executor,
            ObjectProvider<ExternalAiCapabilityContract> aiProvider,
            @Value("${NPDEV_TELEGRAM_BOT_TOKEN:}") String telegramBotToken,
            @Value("${NPDEV_PUBLIC_BASE_URL:}") String publicBaseUrl,
            @Value("${NPDEV_AGENT_VENDOR:gemini}") String agentVendor,
            @Value("${NPDEV_AGENT_MODEL:}") String agentModel,
            @Value("${NPDEV_WHATSAPP_PHONE_NUMBER_ID:}") String whatsappPhoneNumberId,
            @Value("${NPDEV_WHATSAPP_ACCESS_TOKEN:}") String whatsappAccessToken,
            @Value("${NPDEV_WHATSAPP_APP_SECRET:}") String whatsappAppSecret,
            @Value("${NPDEV_WHATSAPP_VERIFY_TOKEN:}") String whatsappVerifyToken
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
        this.whatsappPhoneNumberId = whatsappPhoneNumberId;
        this.whatsappAccessToken = whatsappAccessToken;
        this.whatsappAppSecret = whatsappAppSecret;
        this.whatsappVerifyToken = whatsappVerifyToken;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        var access = modelHolder.get().getAgentAccess();
        boolean telegramWanted = access != null && access.getChannels() != null
                && access.getChannels().getTelegram().isEnabled();
        boolean whatsappWanted = access != null && access.getChannels() != null
                && access.getChannels().getWhatsapp().isEnabled();
        boolean tokenPresent = present(telegramBotToken);

        if (telegramWanted && tokenPresent) {
            TelegramChannel channel = new TelegramChannel(telegramBotToken, mapper, links, conversations(), publicBaseUrl);
            this.telegramChannel = channel;
            channel.start();
        } else if (telegramWanted) {
            LOG.info("agentAccess.channels.telegram is enabled but NPDEV_TELEGRAM_BOT_TOKEN is not set -- "
                    + "Telegram channel not started.");
        }

        if (whatsappWanted) {
            List<String> missing = new ArrayList<>();
            String[][] secrets = {
                    {"NPDEV_WHATSAPP_PHONE_NUMBER_ID", whatsappPhoneNumberId},
                    {"NPDEV_WHATSAPP_ACCESS_TOKEN", whatsappAccessToken},
                    {"NPDEV_WHATSAPP_APP_SECRET", whatsappAppSecret},
                    {"NPDEV_WHATSAPP_VERIFY_TOKEN", whatsappVerifyToken},
            };
            for (String[] secret : secrets) {
                if (!present(secret[1])) {
                    missing.add(secret[0]);
                }
            }
            if (missing.isEmpty()) {
                this.whatsappChannel = new WhatsAppChannel(whatsappPhoneNumberId, whatsappAccessToken,
                        whatsappAppSecret, whatsappVerifyToken, mapper, links, conversations(), publicBaseUrl);
                LOG.info("WhatsApp channel ready: Meta must deliver to <public https address>/api/hooks/agent/whatsapp.");
            } else {
                LOG.info("agentAccess.channels.whatsapp is enabled but " + String.join(", ", missing)
                        + " not set -- WhatsApp channel not started.");
            }
        }

        logAiKeyWarningIfNeeded();
    }

    /** One conversation service for every chat channel -- {@code Speaker.key()} already carries the
     *  channel, so Telegram and WhatsApp histories never mix. */
    private AgentConversationService conversations() {
        if (conversations == null) {
            ExternalAiCapabilityContract ai = aiProvider.getIfAvailable();
            if (ai == null) {
                LOG.warning("A chat channel is enabled but no external-AI provider is wired "
                        + "(set NPDEV_EXTERNALAI_PROVIDER=http); the channel will start but every "
                        + "chat answer will fail.");
            }
            conversations = new AgentConversationService(
                    modelHolder::get, ai == null ? new DenyingAi() : ai, executor, links, mapper,
                    agentVendor, agentModel);
        }
        return conversations;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
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

    /** Null unless the model enables WhatsApp and all four secrets are set. */
    public WhatsAppChannel whatsappChannel() {
        return whatsappChannel;
    }

    @PreDestroy
    public void stop() {
        TelegramChannel channel = telegramChannel;
        if (channel != null) {
            channel.stop();
        }
        WhatsAppChannel whatsapp = whatsappChannel;
        if (whatsapp != null) {
            whatsapp.stop();
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
