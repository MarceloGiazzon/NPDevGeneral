package com.finalexec.collab;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptReadRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROC-5: the comment thread of a record.
 *
 * <p><b>The read gate is the whole security model.</b> Every operation first proves that the CALLER can
 * read the record itself -- {@link ConceptGateway#read} with the caller's own {@link ExecutionContext}, so
 * the concept's {@code access.read} row rules, field access and tenant isolation all apply. A record the
 * caller cannot read (denied, absent, other tenant) and a concept that does not enable
 * {@code collaboration.comments} are indistinguishable: both are {@link Failure#NOT_FOUND}, never a 403,
 * so the thread's existence is not leaked. A generic "Comment" concept could not do this -- row rules
 * cannot express "the caller can read the TARGET row".
 *
 * <p>{@code @username} mentions in a new comment are handed to a {@link MentionNotifier} (the in-app
 * notification bell when one is installed). v1 does not check that the mentioned user can read the record
 * (they get the same 404 as anyone else); the notification carries only the commenter's own text.
 */
public final class RecordCommentService {

    public static final int MAX_BODY_LENGTH = 4000;
    public static final int MAX_MENTIONS = 10;
    public static final int LIST_LIMIT = 500;
    static final String SUPERUSER_ROLE = "SUPERUSER";
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z0-9._-]{2,64})");

    public enum Failure { NOT_FOUND, BODY_INVALID, FORBIDDEN }

    /** A refused operation; {@link #failure()} maps to 404 / 400 / 403. */
    public static final class CommentException extends RuntimeException {
        private final Failure failure;

        public CommentException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** One thread row as the API shows it. */
    public record Item(String id, String author, String body, String kind, String fileJson,
                       long createdAtEpochMs, boolean mine) {
    }

    /** One {@code @mention} found in a new comment. */
    public record Mention(String tenantId, String recipient, String author, String conceptName, String conceptLabel,
                          String recordId, String excerpt, String link, String source) {
    }

    /** Receives mentions after a comment is stored. Must not throw into the caller's request. */
    @FunctionalInterface
    public interface MentionNotifier {
        MentionNotifier NONE = mention -> { };

        void mentioned(Mention mention);
    }

    private final Supplier<CompiledModel> model;
    private final Supplier<ConceptGateway> gateway;
    private final RecordCommentStore store;
    private final MentionNotifier notifier;
    private final LongSupplier clock;

    public RecordCommentService(Supplier<CompiledModel> model, Supplier<ConceptGateway> gateway,
                                RecordCommentStore store, MentionNotifier notifier, LongSupplier clock) {
        this.model = model;
        this.gateway = gateway;
        this.store = store;
        this.notifier = notifier == null ? MentionNotifier.NONE : notifier;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    // ---------------------------------------------------------------- operations --

    public List<Item> list(String conceptName, String recordId, ExecutionContext ctx) {
        CompiledConcept concept = requireReadable(conceptName, recordId, ctx, false);
        List<Item> items = new ArrayList<>();
        for (RecordComment row : store.list(ctx.tenantId(), concept.getName(), recordId, LIST_LIMIT)) {
            items.add(toItem(row, ctx));
        }
        return items;
    }

    public Item add(String conceptName, String recordId, String rawBody, ExecutionContext ctx) {
        CompiledConcept concept = requireReadable(conceptName, recordId, ctx, false);
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.isEmpty() || body.length() > MAX_BODY_LENGTH) {
            throw new CommentException(Failure.BODY_INVALID,
                    "a comment must be 1.." + MAX_BODY_LENGTH + " characters");
        }
        RecordComment row = new RecordComment(UUID.randomUUID().toString(), ctx.tenantId(), concept.getName(), recordId,
                ctx.actorId(), body, RecordComment.KIND_COMMENT, null, clock.getAsLong(), null);
        store.insert(row);
        notifyMentions(concept, row);
        return toItem(row, ctx);
    }

    public void delete(String conceptName, String recordId, String commentId, ExecutionContext ctx) {
        CompiledConcept concept = requireReadable(conceptName, recordId, ctx, false);
        RecordComment row = store.findById(ctx.tenantId(), commentId).orElse(null);
        if (row == null || row.isDeleted() || !row.conceptName().equals(concept.getName())
                || !row.recordId().equals(recordId)) {
            throw new CommentException(Failure.NOT_FOUND, "no such comment");
        }
        if (!row.author().equals(ctx.actorId()) && !ctx.roles().contains(SUPERUSER_ROLE)) {
            throw new CommentException(Failure.FORBIDDEN, "only the author can delete a comment");
        }
        store.softDelete(ctx.tenantId(), commentId, clock.getAsLong());
    }

    // ---------------------------------------------------------------- the gate --

    /**
     * The concept must exist and enable the surface ({@code attachments} selects which flag), and the
     * caller must be able to read the record. Anything else is {@link Failure#NOT_FOUND}.
     */
    CompiledConcept requireReadable(String conceptName, String recordId, ExecutionContext ctx, boolean attachments) {
        CompiledModel current = model.get();
        Optional<CompiledConcept> found = current == null || conceptName == null || recordId == null
                ? Optional.empty() : current.findConcept(conceptName);
        CompiledConcept concept = found.orElse(null);
        if (concept == null || concept.getCollaboration() == null
                || !(attachments ? concept.getCollaboration().attachments() : concept.getCollaboration().comments())) {
            throw notFound();
        }
        ConceptGateway g = gateway.get();
        if (g == null) {
            throw notFound();
        }
        try {
            if (g.read(new ConceptReadRequest(concept.getName(), recordId, ctx.tenantId()), ctx).isEmpty()) {
                throw notFound();
            }
        } catch (CommentException e) {
            throw e;
        } catch (RuntimeException e) {
            // access denied, unknown record, blank id ... -- all look the same from outside
            throw notFound();
        }
        return concept;
    }

    private static CommentException notFound() {
        return new CommentException(Failure.NOT_FOUND, "not found");
    }

    // ---------------------------------------------------------------- mentions --

    static List<String> mentionedUsers(String body, String author) {
        Set<String> distinct = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(body);
        while (matcher.find() && distinct.size() < MAX_MENTIONS) {
            String name = matcher.group(1);
            if (!name.equalsIgnoreCase(author)) {
                distinct.add(name);
            }
        }
        return new ArrayList<>(distinct);
    }

    private void notifyMentions(CompiledConcept concept, RecordComment row) {
        String label = concept.getUi() != null && concept.getUi().getLabel() != null
                && !concept.getUi().getLabel().isBlank() ? concept.getUi().getLabel() : concept.getName();
        String link = "/npdev-business-ui/index.html#concept-" + concept.getName().replaceAll("[^a-zA-Z0-9_-]", "-");
        String excerpt = row.body().length() <= 200 ? row.body() : row.body().substring(0, 200);
        for (String recipient : mentionedUsers(row.body(), row.author())) {
            try {
                notifier.mentioned(new Mention(row.tenantId(), recipient, row.author(), concept.getName(), label,
                        row.recordId(), excerpt, link, "comment:" + concept.getName() + "/" + row.recordId()));
            } catch (RuntimeException ignored) {
                // a failing notifier must never fail or roll back the comment itself
            }
        }
    }

    private static Item toItem(RecordComment row, ExecutionContext ctx) {
        return new Item(row.id(), row.author(), row.body(), row.kind(), row.fileJson(), row.createdAtMs(),
                row.author().equals(ctx.actorId()));
    }
}
