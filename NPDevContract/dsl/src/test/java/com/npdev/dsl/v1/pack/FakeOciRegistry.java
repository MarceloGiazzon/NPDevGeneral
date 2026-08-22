package com.npdev.dsl.v1.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class FakeOciRegistry {

    private final HttpServer server;
    private final Path contentRoot;

    private FakeOciRegistry(HttpServer server, Path contentRoot) {
        this.server = server;
        this.contentRoot = contentRoot;
    }

    static FakeOciRegistry start(Path contentRoot) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String relative = path.substring("/v2".length());

            if (relative.equals("/") || relative.isEmpty()) {
                handleApiBase(exchange);
            } else if (relative.contains("/manifests/")) {
                handleManifest(exchange, relative, contentRoot);
            } else if (relative.contains("/blobs/")) {
                handleBlob(exchange, relative, contentRoot);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        });
        server.setExecutor(null);
        server.start();
        return new FakeOciRegistry(server, contentRoot);
    }

    private static void handleApiBase(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void handleManifest(HttpExchange exchange, String relative, Path contentRoot) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String stripped = relative.startsWith("/") ? relative.substring(1) : relative;
        int idx = stripped.indexOf("/manifests/");
        if (idx < 0) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String repository = stripped.substring(0, idx);
        String reference = stripped.substring(idx + "/manifests/".length());
        Path manifestFile = contentRoot.resolve(repository).resolve("manifests").resolve(reference);
        if (!Files.isRegularFile(manifestFile)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] body = Files.readAllBytes(manifestFile);
        exchange.getResponseHeaders().set("Content-Type", "application/vnd.oci.image.manifest.v1+json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void handleBlob(HttpExchange exchange, String relative, Path contentRoot) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String stripped = relative.startsWith("/") ? relative.substring(1) : relative;
        int idx = stripped.indexOf("/blobs/");
        if (idx < 0) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String repository = stripped.substring(0, idx);
        String digest = stripped.substring(idx + "/blobs/".length());
        Path blobFile = contentRoot.resolve(repository).resolve("blobs").resolve(digest.replace(':', '/'));
        if (!Files.isRegularFile(blobFile)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] body = Files.readAllBytes(blobFile);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    void addManifest(String repository, String reference, String manifestJson) throws IOException {
        Path file = contentRoot.resolve(repository).resolve("manifests").resolve(reference);
        Files.createDirectories(file.getParent());
        Files.writeString(file, manifestJson, StandardCharsets.UTF_8);
    }

    void addBlob(String repository, String digest, byte[] content) throws IOException {
        Path file = contentRoot.resolve(repository).resolve("blobs").resolve(digest.replace(':', '/'));
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }
}
