package com.npdev.dsl.v1.pack;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * PK-5 step 3: {@code git+<transport>://<repo-url>[//<subpath>]@<tag>} -- decision PD6's git
 * substrate. The double slash before an optional subpath disambiguates "clone this whole repo" from
 * "the pack lives at this path inside the repo" (a monorepo of packs, one {@code pack.json} per
 * subdirectory) -- mirrors pip's/go's VCS-URL {@code #subdirectory=}/{@code //}-style conventions
 * rather than inventing a third syntax.
 *
 * <p>The tag is found by the LAST {@code @} in the string (after the scheme's own {@code ://}),
 * not the first -- {@code git+ssh://git@host/repo@v1} has a legitimate {@code user@host} earlier in
 * the URL, and only the trailing one is ever the tag delimiter.
 *
 * <p>{@code file} is a first-class transport, not an afterthought: it is what makes the git
 * substrate provable end-to-end without any real network access (a local repo on disk, cloned by
 * git itself with zero network I/O) -- see {@code RemotePackFetcherGitLiveTest}.
 */
public record GitCoordinate(String raw, String transport, String repoUrl, String subpath, String tag)
        implements PackCoordinate {

    private static final Set<String> ALLOWED_TRANSPORTS = Set.of("https", "http", "ssh", "file", "git");
    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]*$");

    static GitCoordinate parse(String from) {
        String rest = from.substring("git+".length());
        int schemeSep = rest.indexOf("://");
        if (schemeSep <= 0) {
            throw new IllegalArgumentException(
                    "git+ coordinate must be 'git+<transport>://<url>[//<subpath>]@<tag>': " + from);
        }
        String transport = rest.substring(0, schemeSep);
        if (!ALLOWED_TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("git+ transport must be one of " + ALLOWED_TRANSPORTS + ", got '"
                    + transport + "': " + from);
        }
        String afterScheme = rest.substring(schemeSep + 3);

        int lastAt = afterScheme.lastIndexOf('@');
        if (lastAt < 0 || lastAt == afterScheme.length() - 1) {
            throw new IllegalArgumentException("git+ coordinate must end with '@<tag>': " + from);
        }
        String tag = afterScheme.substring(lastAt + 1);
        if (!TAG_PATTERN.matcher(tag).matches()) {
            throw new IllegalArgumentException("git+ tag is not a valid ref name: '" + tag + "' in " + from);
        }
        String urlAndSubpath = afterScheme.substring(0, lastAt);

        String repoUrl;
        String subpath;
        int subpathSep = urlAndSubpath.indexOf("//");
        if (subpathSep >= 0) {
            repoUrl = urlAndSubpath.substring(0, subpathSep);
            subpath = urlAndSubpath.substring(subpathSep + 2);
        } else {
            repoUrl = urlAndSubpath;
            subpath = "";
        }
        if (repoUrl.isBlank()) {
            throw new IllegalArgumentException("git+ coordinate must name a non-blank repository URL: " + from);
        }
        if (subpath.contains("..")) {
            throw new IllegalArgumentException("git+ subpath must not contain '..' path-traversal segments: " + from);
        }

        return new GitCoordinate(from, transport, repoUrl, subpath, tag);
    }

    /** The URL git itself should clone -- {@code transport://repoUrl}, subpath and tag stripped. */
    public String fullUrl() {
        return transport + "://" + repoUrl;
    }

    @Override
    public String toString() {
        return "git+" + transport + "://" + repoUrl + (subpath.isEmpty() ? "" : "//" + subpath) + "@" + tag;
    }
}
