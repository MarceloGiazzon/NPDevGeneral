package com.finalexec.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.JwtSigner;
import com.finalexec.auth.LoginController;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.runtime.support.IdentityRoleLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AGENT-1 (A6.2): links a Telegram/WhatsApp account to an app user via a short, human-typeable
 * code, and mints the short-lived JWT a chat channel uses to call the app's own REST API as the
 * linked user. All in-memory, on purpose (A1's design table): conversation memory, pending
 * confirmations and link codes are a single app instance's working state -- a restart forgetting
 * them is an acceptable trade for not adding a new persisted table.
 */
@Component
public final class AgentLinkService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final long CODE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_LIVE_CODES_PER_USER = 3;
    private static final int MAX_BAD_ATTEMPTS = 5;
    private static final long LOCKOUT_WINDOW_MILLIS = 10 * 60 * 1000L;
    private static final long SHORT_LIVED_TOKEN_SECONDS = 120;

    public record Speaker(String tenantId, String username, Set<String> roles) {
    }

    public record CreatedCode(String code, Instant expiresAt) {
    }

    private static final class LinkCode {
        final String code;
        final String tenantId;
        final String username;
        final long expiresAtMillis;
        volatile boolean used;

        LinkCode(String code, String tenantId, String username, long expiresAtMillis) {
            this.code = code;
            this.tenantId = tenantId;
            this.username = username;
            this.expiresAtMillis = expiresAtMillis;
        }

        boolean liveNow() {
            return !used && expiresAtMillis > System.currentTimeMillis();
        }
    }

    private final ModelHolder modelHolder;
    private final DataSource dataSource;
    private final ExternalIdentityStore store;
    private final PrivateKey privateKey;
    private final ObjectMapper objectMapper;
    private final String issuer;
    private final String audience;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, List<LinkCode>> codesByUserKey = new ConcurrentHashMap<>();
    private final Map<String, LinkCode> codesByCode = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> badAttemptsBySubjectKey = new ConcurrentHashMap<>();

    public AgentLinkService(
            DataSource dataSource,
            ModelHolder modelHolder,
            ObjectMapper objectMapper,
            @Value("${npdev.auth.jwt.private-key-path:}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience
    ) throws Exception {
        this.dataSource = dataSource;
        this.modelHolder = modelHolder;
        this.store = new ExternalIdentityStore(dataSource);
        this.objectMapper = objectMapper;
        this.issuer = issuer;
        this.audience = audience;
        this.privateKey = (privateKeyPath == null || privateKeyPath.isBlank())
                ? null
                : JwtSigner.loadPrivateKey(LoginController.readKeyFile(new DefaultResourceLoader(), privateKeyPath));
    }

    public CreatedCode createCode(String tenantId, String username) {
        String userKey = userKey(tenantId, username);
        List<LinkCode> live = codesByUserKey.computeIfAbsent(userKey, k -> new CopyOnWriteArrayList<>());
        live.removeIf(c -> !c.liveNow());
        while (live.size() >= MAX_LIVE_CODES_PER_USER) {
            LinkCode oldest = live.remove(0);
            codesByCode.remove(oldest.code);
        }
        String code = randomCode();
        long expiresAtMillis = System.currentTimeMillis() + CODE_TTL_MILLIS;
        LinkCode entry = new LinkCode(code, tenantId, username, expiresAtMillis);
        live.add(entry);
        codesByCode.put(code, entry);
        return new CreatedCode(code, Instant.ofEpochMilli(expiresAtMillis));
    }

    /** @return a user-facing sentence: success, or why it failed. Never throws. */
    public String completeLink(String provider, String subject, String code) {
        String subjectKey = provider + ":" + subject;
        if (isLockedOut(subjectKey)) {
            return "Too many wrong codes. Wait a few minutes and create a new one on the Connect page.";
        }
        LinkCode entry = code == null ? null : codesByCode.get(code.trim().toUpperCase(java.util.Locale.ROOT));
        if (entry == null || !entry.liveNow()) {
            recordBadAttempt(subjectKey);
            return "This code was already used or has expired. Create a new one on the Connect page.";
        }
        ExternalIdentityStore.Tables tables = ExternalIdentityStore.Tables.resolve(modelHolder.get());
        if (tables == null) {
            return "This app has no identity pack composed; account linking is unavailable.";
        }
        Optional<String> userId = store.findUserIdByUsername(tables, entry.username, entry.tenantId);
        if (userId.isEmpty()) {
            return "Your account could not be resolved. Create a new code on the Connect page.";
        }
        Optional<String> existingOwner = store.findLinkOwner(tables, provider, subject);
        if (existingOwner.isPresent() && !existingOwner.get().equals(userId.get())) {
            return "This " + provider + " account is already connected to another user. Send /unlink first.";
        }
        entry.used = true;
        if (existingOwner.isPresent()) {
            return "Connected! You are " + entry.username + ".";
        }
        boolean inserted = store.insertLink(tables, userId.get(), provider, subject, entry.tenantId);
        if (!inserted) {
            return "Could not connect right now. Try again in a moment.";
        }
        return "Connected! You are " + entry.username + ".";
    }

    public boolean unlink(String provider, String subject) {
        ExternalIdentityStore.Tables tables = ExternalIdentityStore.Tables.resolve(modelHolder.get());
        if (tables == null) {
            return false;
        }
        return store.deleteLink(tables, provider, subject);
    }

    /** Scoped unlink for the {@code agent-link.html} page / {@code DELETE /api/agent/links/{provider}}:
     *  removes the CALLING user's own link, never another user's. */
    public boolean unlinkForUser(String provider, String tenantId, String username) {
        ExternalIdentityStore.Tables tables = ExternalIdentityStore.Tables.resolve(modelHolder.get());
        if (tables == null) {
            return false;
        }
        return store.findUserIdByUsername(tables, username, tenantId)
                .map(userId -> store.deleteLinkForUser(tables, userId, provider))
                .orElse(false);
    }

    public Optional<Speaker> speakerFor(String provider, String subject) {
        ExternalIdentityStore.Tables tables = ExternalIdentityStore.Tables.resolve(modelHolder.get());
        if (tables == null) {
            return Optional.empty();
        }
        Optional<String> userId = store.findLinkOwner(tables, provider, subject);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        Optional<ExternalIdentityStore.ActiveUser> active = store.findActiveUserById(tables, userId.get());
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Set<String> roles = IdentityPackTableNames.tryResolve(modelHolder.get())
                .map(t -> IdentityRoleLookup.rolesFor(dataSource, t, active.get().tenantId(), active.get().username()))
                .orElseGet(Set::of);
        return Optional.of(new Speaker(active.get().tenantId(), active.get().username(), roles));
    }

    public List<String> linkedProviders(String tenantId, String username) {
        ExternalIdentityStore.Tables tables = ExternalIdentityStore.Tables.resolve(modelHolder.get());
        if (tables == null) {
            return List.of();
        }
        return store.findUserIdByUsername(tables, username, tenantId)
                .map(userId -> store.linkedProviders(tables, userId))
                .orElseGet(List::of);
    }

    /** A 120-second JWT for the linked user -- just long enough for one tool-calling turn's chain
     *  of loopback calls. Current roles + token version, so a revoked/role-changed user is refused
     *  by the app's own permission checks on the very next turn. */
    public Optional<String> mintShortLivedToken(String tenantId, String username) {
        if (privateKey == null) {
            return Optional.empty();
        }
        JwtSigner signer = new JwtSigner(objectMapper, privateKey, issuer, audience, SHORT_LIVED_TOKEN_SECONDS);
        Optional<IdentityPackTableNames> tables = IdentityPackTableNames.tryResolve(modelHolder.get());
        Set<String> roles = tables.map(t -> IdentityRoleLookup.rolesFor(dataSource, t, tenantId, username))
                .orElseGet(Set::of);
        int tokenVersion = tables.map(t -> IdentityRoleLookup.tokenVersion(dataSource, t, tenantId, username))
                .orElse(0);
        return Optional.of(signer.sign(tenantId, username, roles, tokenVersion));
    }

    private boolean isLockedOut(String subjectKey) {
        Deque<Long> attempts = badAttemptsBySubjectKey.get(subjectKey);
        if (attempts == null) {
            return false;
        }
        pruneOldAttempts(attempts);
        return attempts.size() >= MAX_BAD_ATTEMPTS;
    }

    private void recordBadAttempt(String subjectKey) {
        Deque<Long> attempts = badAttemptsBySubjectKey.computeIfAbsent(subjectKey, k -> new ArrayDeque<>());
        synchronized (attempts) {
            pruneOldAttempts(attempts);
            attempts.addLast(System.currentTimeMillis());
        }
    }

    private void pruneOldAttempts(Deque<Long> attempts) {
        long cutoff = System.currentTimeMillis() - LOCKOUT_WINDOW_MILLIS;
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) {
                attempts.pollFirst();
            }
        }
    }

    private static String userKey(String tenantId, String username) {
        return (tenantId == null ? "" : tenantId) + ":" + (username == null ? "" : username);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
