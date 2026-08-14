package com.npdev.dsl.v1.pack;

/**
 * PK-5 step 2 ("Distribution", {@code PACK-ROADMAP.md} card PK-5): the mechanical guard behind
 * "{@code npdev pack add|update} may touch the network; {@code npdev generate} may not, ever."
 *
 * <p>There are exactly two instances, both constants -- this is deliberately not a boolean
 * parameter, so every call site reads as a named policy rather than an unexplained {@code true}/
 * {@code false}. {@link #DENIED} is what {@code PackDependencyGraphWalker}'s real (non-CLI)
 * {@code resolve} overload hardcodes for the generate/validate path -- not read from config, not
 * overridable by a caller -- so a future edit that adds a network call there still has to pass a
 * {@code NetworkPolicy} through, and the one already hardcoded there refuses it. {@link #ALLOWED}
 * is constructed only by the two CLI entry points that are explicitly allowed to touch the network
 * ({@code PackAddMain}/{@code PackUpdateMain}, via {@code PackUpdateMain} delegating to the former)
 * -- {@code PackListMain}/{@code PackWhyMain} pass {@link #DENIED} too, since neither ever needs to
 * fetch (they only ever read an existing lock or run a local-file-only dry run).
 *
 * <p>{@link #requireAllowed(String)} is called as the FIRST statement of {@link RemotePackFetcher
 * #fetch}, before any URL is built, process started, or socket opened -- for every coordinate
 * scheme, including one this slice never actually reaches the network for (OCI, see {@code
 * RemotePackFetcher}'s own doc). That ordering is what makes the guard "mechanical" rather than
 * a convention: it is not possible to reach a network operation without the check having already
 * run and passed.
 */
public final class NetworkPolicy {

    public static final NetworkPolicy DENIED = new NetworkPolicy(false,
            "generate/validate path -- hermetic by design, see PACK-ROADMAP.md card PK-5 step 2");
    public static final NetworkPolicy ALLOWED = new NetworkPolicy(true,
            "pack add/update -- the one explicit network phase");

    private final boolean allowed;
    private final String context;

    private NetworkPolicy(boolean allowed, String context) {
        this.allowed = allowed;
        this.context = context;
    }

    public boolean isAllowed() {
        return allowed;
    }

    /**
     * @param operationDescription what the caller is about to do, folded into the refusal message
     *                             (e.g. {@code "fetch pack from git+https://.../identity@2.1.0"}).
     * @throws NetworkPolicyViolationException iff this policy is {@link #DENIED}.
     */
    public void requireAllowed(String operationDescription) throws NetworkPolicyViolationException {
        if (!allowed) {
            throw new NetworkPolicyViolationException(
                    "Network access denied (" + context + "): refusing to " + operationDescription
                            + " -- npdev generate/validate must be fully offline; run 'npdev pack add' or "
                            + "'npdev pack update' first to populate the local content-addressed cache.");
        }
    }

    @Override
    public String toString() {
        return allowed ? "NetworkPolicy.ALLOWED" : "NetworkPolicy.DENIED";
    }
}
