package com.finalexec.api;

import com.finalexec.publicread.PublicReadRateLimiter;
import com.finalexec.publicread.PublicReadService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6: the HTTP mapping {@link PublicReadController} puts over {@link PublicReadService} -- every
 * "not public" outcome is the same 404, a bad filter is 400, an unwired store is 503, images carry
 * the sandboxing headers, and the per-client rate limit answers 429 + Retry-After keyed by remote
 * address unless X-Forwarded-For is explicitly trusted. The service's own guarantees (projection,
 * where push-down, disabled tenant) are proven in runtimehost-core's PublicReadServiceTest.
 */
class PublicReadControllerTest {

    private final PublicReadService service = mock(PublicReadService.class);

    private MockMvc mvc(int perMinute, boolean trustForwardedFor) {
        return MockMvcBuilders.standaloneSetup(
                new PublicReadController(service, new PublicReadRateLimiter(perMinute), trustForwardedFor)).build();
    }

    @Test
    void catalogAndAggregateServeWhatTheServiceReturnsWithPublicCaching() throws Exception {
        when(service.catalog()).thenReturn(List.of(Map.of("concept", "Mosaic", "fields", List.of("title"))));
        when(service.aggregate("MosaicBoard", "m1")).thenReturn(Map.of("id", "m1"));
        MockMvc mvc = mvc(100, false);

        mvc.perform(get("/api/public/concepts"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, public"))
                .andExpect(jsonPath("$[0].concept").value("Mosaic"));
        mvc.perform(get("/api/v1/public/aggregate/MosaicBoard/m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m1"));
    }

    @Test
    void notPublicIsA404BadFilterA400AndAnUnwiredStoreA503() throws Exception {
        when(service.get("Draft", "x")).thenThrow(new PublicReadService.NotPublicException("not found"));
        when(service.page(eq("Mosaic"), any())).thenThrow(new IllegalArgumentException("field 'owner' is not public"));
        when(service.aggregate("MosaicBoard", "m1")).thenThrow(new IllegalStateException("no gateway"));
        MockMvc mvc = mvc(100, false);

        mvc.perform(get("/api/public/concepts/Draft/x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("not found"));
        mvc.perform(get("/api/public/concepts/Mosaic").param("owner", "tito"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("field 'owner' is not public"));
        mvc.perform(get("/api/public/aggregate/MosaicBoard/m1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("public read unavailable"));
    }

    @Test
    void imagesAreServedSandboxedAndANonPublicImageIsA404() throws Exception {
        byte[] png = "png-bytes".getBytes(StandardCharsets.UTF_8);
        when(service.image("Cap", "c1", "photo")).thenReturn(new PublicReadService.Image(png, "image/png"));
        when(service.image("Cap", "c2", "photo")).thenThrow(new PublicReadService.NotPublicException("not found"));
        MockMvc mvc = mvc(100, false);

        mvc.perform(get("/api/public/concepts/Cap/c1/image/photo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        mvc.perform(get("/api/public/concepts/Cap/c2/image/photo"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRateLimitIsPerRemoteAddressAndIgnoresForwardedForUnlessTrusted() throws Exception {
        when(service.catalog()).thenReturn(List.of());
        MockMvc untrusted = mvc(1, false);

        untrusted.perform(get("/api/public/concepts").header("X-Forwarded-For", "10.0.0.1"))
                .andExpect(status().isOk());
        // A spoofed header does not buy a fresh bucket: the key is still the remote address.
        untrusted.perform(get("/api/public/concepts").header("X-Forwarded-For", "10.0.0.2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        untrusted.perform(get("/api/public/concepts/Cap/c1/image/photo"))
                .andExpect(status().isTooManyRequests());

        MockMvc trusted = mvc(1, true);
        trusted.perform(get("/api/public/concepts").header("X-Forwarded-For", "10.0.0.1, 172.16.0.1"))
                .andExpect(status().isOk());
        trusted.perform(get("/api/public/concepts").header("X-Forwarded-For", "10.0.0.2"))
                .andExpect(status().isOk());
        trusted.perform(get("/api/public/concepts").header("X-Forwarded-For", "10.0.0.1"))
                .andExpect(status().isTooManyRequests());
    }
}
