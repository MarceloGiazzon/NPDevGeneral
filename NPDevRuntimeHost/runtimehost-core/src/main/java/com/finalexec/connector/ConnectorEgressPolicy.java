package com.finalexec.connector;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * INT-2: which outbound hosts a connector may reach. Rules, in order: the operator switch, the final
 * host must equal the declared base-URL host (no host switching through path/query parameters),
 * https unless loopback or the operator allowed plain http, and the operator allow-list when set.
 * {@link #check} returns a denial message (never throws, never returns null for a denial).
 */
public final class ConnectorEgressPolicy {

    private final boolean enabled;
    private final Set<String> allowedHosts;
    private final boolean allowInsecureHttp;

    public ConnectorEgressPolicy(boolean enabled, Set<String> allowedHosts, boolean allowInsecureHttp) {
        this.enabled = enabled;
        this.allowedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.allowInsecureHttp = allowInsecureHttp;
    }

    /** @return null when the call is allowed, otherwise a human-readable denial. */
    public String check(String baseUrl, URI uri) {
        if (!enabled) {
            return "Outbound connectors are disabled on this deployment (npdev.connectors.enabled=false)";
        }
        String baseHost;
        try {
            baseHost = host(URI.create(baseUrl == null ? "" : baseUrl));
        } catch (IllegalArgumentException invalid) {
            return "Connector base URL is not a valid URL";
        }
        String host = host(uri);
        if (host.isEmpty() || baseHost.isEmpty()) {
            return "Connector URL has no host";
        }
        if (!host.equals(baseHost)) {
            return "Request host '" + host + "' differs from the connector's declared base host '" + baseHost + "'";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && (allowInsecureHttp || loopback))) {
            return "Connector must use https (set npdev.connectors.allow-insecure-http=true to permit plain http)";
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host)) {
            return "Host '" + host + "' is not in npdev.connectors.allowed-hosts";
        }
        return null;
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().trim().toLowerCase(Locale.ROOT);
    }
}
