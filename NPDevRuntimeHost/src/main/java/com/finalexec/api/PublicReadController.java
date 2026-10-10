package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.filestore.TenantFileReader;
import com.finalexec.npdev.service.TenantRegistryService;
import com.finalexec.publicread.PublicReadRateLimiter;
import com.finalexec.publicread.PublicReadService;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.ports.FileStoreContract;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * P6 (Pigmentampas public gallery, G4): the anonymous, READ-ONLY API -- only concepts whose model
 * declares {@code access.public} exist here, projected onto their allow-listed fields (see
 * {@link PublicReadService} for every guarantee). GET only: there is no write mapping on this path
 * at all, and {@code JwtBearerAuthFilter} / the api-key filter exempt ONLY {@code GET}/{@code HEAD}
 * under {@code /api/public/}, so any other method still needs a credential and then finds no route.
 *
 * <pre>
 *   GET /api/public/concepts                              -> [{concept, fields}]
 *   GET /api/public/concepts/{concept}?limit&offset&sort&direction&field=value
 *   GET /api/public/concepts/{concept}/{id}
 *   GET /api/public/concepts/{concept}/{id}/image/{field} -> image bytes (images only)
 *   GET /api/public/aggregate/{aggregate}/{rootId}        -> tree of public members
 * </pre>
 *
 * Every request is rate-limited per client ({@code npdev.public-read.rate-limit-per-minute}, 429 +
 * {@code Retry-After}); data is read from ONE tenant ({@code npdev.public-read.tenant-id}, default
 * {@code dev} -- the same tenant LoginController signs a tenant-less login into). Anything
 * not public -- unknown concept, private concept, filtered-out row, non-public field -- is the same 404.
 */
@RestController
@RequestMapping({"/api/public", "/api/v1/public"})
public class PublicReadController {

    private final PublicReadService service;
    private final PublicReadRateLimiter rateLimiter;
    private final boolean trustForwardedFor;

    @Autowired
    public PublicReadController(
            ModelHolder modelHolder,
            ObjectProvider<ConceptGateway> conceptGateway,
            ObjectProvider<FileStoreContract> fileStore,
            ObjectProvider<TenantRegistryService> tenantRegistry,
            @Value("${npdev.public-read.tenant-id:${npdev.tenant.default-id:dev}}") String tenantId,
            @Value("${npdev.public-read.rate-limit-per-minute:240}") int rateLimitPerMinute,
            @Value("${npdev.public-read.trust-forwarded-for:false}") boolean trustForwardedFor
    ) {
        this(new PublicReadService(modelHolder::get, conceptGateway::getIfAvailable,
                        new TenantFileReader(fileStore::getIfAvailable), tenantId, "/api/public",
                        tenant -> {
                            TenantRegistryService registry = tenantRegistry.getIfAvailable();
                            return registry == null || registry.isActive(tenant);
                        }),
                new PublicReadRateLimiter(rateLimitPerMinute), trustForwardedFor);
    }

    PublicReadController(PublicReadService service, PublicReadRateLimiter rateLimiter, boolean trustForwardedFor) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.trustForwardedFor = trustForwardedFor;
    }

    @GetMapping("/concepts")
    public ResponseEntity<?> catalog(HttpServletRequest request) {
        return guarded(request, () -> service.catalog());
    }

    @GetMapping("/concepts/{concept}")
    public ResponseEntity<?> page(HttpServletRequest request, @PathVariable String concept) {
        return guarded(request, () -> service.page(concept, request.getParameterMap()));
    }

    @GetMapping("/concepts/{concept}/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable String concept, @PathVariable String id) {
        return guarded(request, () -> service.get(concept, id));
    }

    @GetMapping("/concepts/{concept}/{id}/image/{field}")
    public ResponseEntity<?> image(HttpServletRequest request, @PathVariable String concept,
                                   @PathVariable String id, @PathVariable String field) {
        ResponseEntity<?> limited = rateLimited(request);
        if (limited != null) {
            return limited;
        }
        try {
            PublicReadService.Image image = service.image(concept, id, field);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .body(image.bytes());
        } catch (PublicReadService.NotPublicException notPublic) {
            return notFound();
        }
    }

    @GetMapping("/aggregate/{aggregate}/{rootId}")
    public ResponseEntity<?> aggregate(HttpServletRequest request, @PathVariable String aggregate,
                                       @PathVariable String rootId) {
        return guarded(request, () -> service.aggregate(aggregate, rootId));
    }

    private ResponseEntity<?> guarded(HttpServletRequest request, Supplier<Object> read) {
        ResponseEntity<?> limited = rateLimited(request);
        if (limited != null) {
            return limited;
        }
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                    .body(read.get());
        } catch (PublicReadService.NotPublicException notPublic) {
            return notFound();
        } catch (IllegalArgumentException badRequest) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(badRequest.getMessage())));
        } catch (IllegalStateException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "public read unavailable"));
        }
    }

    private ResponseEntity<?> rateLimited(HttpServletRequest request) {
        long retryAfter = rateLimiter.tryAcquire(clientKey(request));
        if (retryAfter <= 0) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfter))
                .body(Map.of("message", "too many requests"));
    }

    /** Remote address by default: X-Forwarded-For is caller-controlled unless a trusted proxy sets it. */
    private String clientKey(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "not found"));
    }
}
