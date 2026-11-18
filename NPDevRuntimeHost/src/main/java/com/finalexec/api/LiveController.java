package com.finalexec.api;

import com.finalexec.live.LiveChange;
import com.finalexec.live.LiveChangeHub;
import com.finalexec.live.LiveStreamService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * FORM P5-T5: {@code GET /api/live?concepts=Ride,RideOffer} -- a server-sent-events stream of "row changed" notices
 * ({@code event: changed}, {@code data: {"concept":"Ride","id":"...","version":3}}), pushed AFTER the change commits and
 * carrying no row data. The client reads it with {@code fetch()} (an {@code EventSource} cannot send the bearer token)
 * and refetches through the normal REST API, so every access rule applies to what it then sees.
 *
 * <p>Same authentication as every {@code /api} route (the filters cover it; no exemption). The caller's tenant scopes the
 * stream. A concept is streamed only if the caller can list it -- tested with the identical gateway call the REST list
 * makes (one row, discarded); unknown and unreadable names are dropped silently, none left is a 403, more than
 * {@link LiveStreamService#MAX_CONCEPTS} names or a malformed one is a 400, and a user's sixth concurrent stream is a 429.
 * A {@code : heartbeat} comment goes out every 20 s; the emitter times out after {@value #STREAM_TIMEOUT_MILLIS} ms so a
 * long-lived stream re-authenticates when the client reconnects. Streams are removed from the hub on completion, timeout
 * and error.
 *
 * <p>Row-level scoping ({@code access.read}) is NOT applied to the notice itself: a caller who may list the concept learns
 * that some row of it in their tenant changed, with its id and version, and reads it only if the REST read lets them.
 */
@RestController
public class LiveController {

    static final long STREAM_TIMEOUT_MILLIS = 15L * 60L * 1000L;

    private final LiveStreamService streams;
    private final RuntimeContextService runtimeContextService;
    private final ConceptGateway conceptGateway;

    @Autowired
    public LiveController(LiveChangeHub hub, RuntimeContextService runtimeContextService, ConceptGateway conceptGateway) {
        this.streams = new LiveStreamService(hub);
        this.runtimeContextService = runtimeContextService;
        this.conceptGateway = conceptGateway;
    }

    // No `produces`: a refusal (400/403/429) then stays a plain status even though the client asks for text/event-stream.
    @GetMapping("/api/live")
    public SseEmitter live(
            @RequestParam(name = "concepts", required = false) String concepts,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (conceptGateway == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        ExecutionContext context = runtimeContextService.currentContext(request);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        LiveChangeHub.Sink sink = new LiveChangeHub.Sink() {
            @Override
            public void changed(LiveChange change) throws IOException {
                emitter.send(SseEmitter.event().name("changed").data(change.toJson()));
            }

            @Override
            public void heartbeat() throws IOException {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
        };
        LiveStreamService.Opened opened;
        try {
            opened = streams.open(context.tenantId(), context.actorId(), concepts,
                    name -> mayList(name, context), sink);
        } catch (LiveStreamService.Refused refused) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.valueOf(refused.status()), refused.getMessage());
        }
        LiveChangeHub.Subscription subscription = opened.subscription();
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(() -> { subscription.close(); emitter.complete(); });
        emitter.onError(problem -> subscription.close());
        // a stream must never be buffered, cached or compressed on its way to the browser
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | RuntimeException failed) {
            subscription.close();
            throw failed;
        }
        return emitter;
    }

    /** The gate the REST list applies (permission + row/field rules), probed with a one-row page that is thrown away. */
    private boolean mayList(String concept, ExecutionContext context) {
        try {
            conceptGateway.query(new ConceptQueryRequest(concept, context.tenantId(),
                    new ConceptQuery(List.of(), List.of(), 0, 1)), context);
            return true;
        } catch (RuntimeException deniedOrUnknown) {
            return false;
        }
    }
}
