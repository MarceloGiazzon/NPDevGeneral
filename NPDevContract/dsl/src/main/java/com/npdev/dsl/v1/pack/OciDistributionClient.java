package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class OciDistributionClient {

    private static final String MANIFEST_ACCEPT = "application/vnd.oci.image.manifest.v1+json";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    OciDistributionClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    String fetchManifest(String registryBaseUrl, String repository, String reference) throws IOException {
        String url = registryBaseUrl + "/v2/" + repository + "/manifests/" + reference;
        HttpRequest request = buildGet(url).header("Accept", MANIFEST_ACCEPT).build();
        HttpResponse<String> response = send(request);

        if (response.statusCode() == 401) {
            String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse("");
            if (wwwAuth.startsWith("Bearer ")) {
                String token = fetchToken(wwwAuth.substring("Bearer ".length()));
                HttpRequest retry = buildGet(url)
                        .header("Accept", MANIFEST_ACCEPT)
                        .header("Authorization", "Bearer " + token)
                        .build();
                response = send(retry);
            }
        }

        if (response.statusCode() == 404) {
            throw new IOException("manifest not found: " + repository + ":" + reference);
        }
        if (response.statusCode() != 200) {
            throw new IOException("manifest fetch failed with HTTP " + response.statusCode()
                    + " for " + repository + ":" + reference);
        }
        return response.body();
    }

    byte[] fetchBlob(String registryBaseUrl, String repository, String digest) throws IOException {
        String url = registryBaseUrl + "/v2/" + repository + "/blobs/" + digest;
        HttpRequest request = buildGet(url).build();
        HttpResponse<byte[]> response = sendBytes(request);

        if (response.statusCode() == 404) {
            throw new IOException("blob not found: " + digest);
        }
        if (response.statusCode() != 200) {
            throw new IOException("blob fetch failed with HTTP " + response.statusCode() + " for " + digest);
        }
        return response.body();
    }

    private String fetchToken(String challenge) throws IOException {
        String realm = extractParam(challenge, "realm");
        if (realm == null) {
            throw new IOException("Bearer challenge missing realm: " + challenge);
        }
        String service = extractParam(challenge, "service");
        String scope = extractParam(challenge, "scope");

        StringBuilder tokenUrl = new StringBuilder(realm);
        char sep = realm.contains("?") ? '&' : '?';
        if (service != null) {
            tokenUrl.append(sep).append("service=").append(URLEncoder.encode(service, StandardCharsets.UTF_8));
            sep = '&';
        }
        if (scope != null) {
            tokenUrl.append(sep).append("scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl.toString()))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IOException("token endpoint returned HTTP " + response.statusCode());
        }
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode token = json.get("token");
        if (token == null) {
            token = json.get("access_token");
        }
        if (token == null) {
            throw new IOException("token response contained no 'token' or 'access_token' field");
        }
        return token.asText();
    }

    private static String extractParam(String challenge, String name) {
        for (String part : challenge.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                String value = trimmed.substring(name.length() + 1);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private HttpRequest.Builder buildGet(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }
}
