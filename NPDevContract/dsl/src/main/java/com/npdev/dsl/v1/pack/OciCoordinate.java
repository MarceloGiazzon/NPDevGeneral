package com.npdev.dsl.v1.pack;

import java.util.regex.Pattern;

/**
 * PK-5 step 3: {@code oci://registry[:port]/repository/path:tag} or {@code
 * oci://registry/repository@sha256:<64-hex>}. Parsing follows the same "last {@code /} then last
 * {@code :} or {@code @}" rule the Docker/OCI reference grammar uses to disambiguate a registry
 * port's colon from a tag's colon -- e.g. {@code registry.example.com:5000/org/repo:1.0} has a port
 * AND a tag, both spelled with {@code :}.
 *
 * <p>{@link RemotePackFetcher} does not implement an actual OCI Distribution API pull in this slice
 * (see its own doc comment) -- this class is the coordinate GRAMMAR only, fully real and fully
 * tested without touching a registry.
 */
public record OciCoordinate(String raw, String registry, String repository, String reference, boolean referenceIsDigest)
        implements PackCoordinate {

    private static final Pattern DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern REGISTRY_PATTERN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]+)?$");
    private static final Pattern REPOSITORY_SEGMENT_PATTERN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$");
    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    static OciCoordinate parse(String from) {
        String rest = from.substring("oci://".length());
        if (rest.isBlank()) {
            throw new IllegalArgumentException("oci:// coordinate must name a registry and repository: " + from);
        }

        String pathAndReference;
        String reference;
        boolean referenceIsDigest;
        int at = rest.indexOf('@');
        int lastSlash = rest.lastIndexOf('/');
        if (at >= 0 && at > lastSlash) {
            pathAndReference = rest.substring(0, at);
            reference = rest.substring(at + 1);
            if (!DIGEST_PATTERN.matcher(reference).matches()) {
                throw new IllegalArgumentException("oci:// digest reference must match 'sha256:<64 hex chars>': " + from);
            }
            referenceIsDigest = true;
        } else {
            // Tag colon must be AFTER the last '/', so a registry port's colon (which is always
            // before the first '/') is never mistaken for it.
            int lastSlashForTag = rest.lastIndexOf('/');
            int tagColon = rest.indexOf(':', Math.max(lastSlashForTag, 0));
            if (tagColon < 0) {
                throw new IllegalArgumentException(
                        "oci:// coordinate must end with ':<tag>' or '@sha256:<digest>': " + from);
            }
            pathAndReference = rest.substring(0, tagColon);
            reference = rest.substring(tagColon + 1);
            if (!TAG_PATTERN.matcher(reference).matches()) {
                throw new IllegalArgumentException("oci:// tag is not a valid reference: " + reference);
            }
            referenceIsDigest = false;
        }

        int splitSlash = pathAndReference.indexOf('/');
        if (splitSlash < 0) {
            throw new IllegalArgumentException(
                    "oci:// coordinate must be 'oci://registry/repository[/more...]:tag': " + from);
        }
        String registry = pathAndReference.substring(0, splitSlash);
        String repository = pathAndReference.substring(splitSlash + 1);
        if (!REGISTRY_PATTERN.matcher(registry).matches()) {
            throw new IllegalArgumentException("oci:// registry host is invalid: " + registry);
        }
        if (repository.isBlank()) {
            throw new IllegalArgumentException("oci:// coordinate must name a non-blank repository path: " + from);
        }
        for (String segment : repository.split("/", -1)) {
            if (!REPOSITORY_SEGMENT_PATTERN.matcher(segment).matches()) {
                throw new IllegalArgumentException("oci:// repository path segment is invalid: '" + segment + "' in " + from);
            }
        }

        return new OciCoordinate(from, registry, repository, reference, referenceIsDigest);
    }

    @Override
    public String toString() {
        return "oci://" + registry + "/" + repository + (referenceIsDigest ? "@" : ":") + reference;
    }
}
