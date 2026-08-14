package com.npdev.kernel.concepts;

import java.util.List;

/**
 * RUN-1 (R8a): the result of a bounded, "list()"-shaped read that can no longer assume it saw the
 * whole tenant table -- {@code records} is what the caller gets back (never more than the caller's
 * own {@code maxRows}), and {@code truncated} is {@code true} exactly when more rows existed than
 * fit. Unlike {@link ConceptPage} (an explicit, caller-requested offset/limit window, where "more
 * exists" is completely expected and communicated via {@code hasMore}/{@code total}), this type
 * exists for a read path that historically had NO window at all -- {@code truncated=true} here means
 * "this response is quietly incomplete unless you look at this flag," which is why
 * {@code ConceptGateway#listCapped}/{@code ConceptStore#findAllCapped} callers are expected to
 * surface it to the caller (see the generated REST list() endpoint's {@code X-List-Truncated}
 * header) rather than swallow it the way a plain {@code list()} always has.
 */
public record ConceptListSlice<T>(List<T> records, boolean truncated) {
    public ConceptListSlice {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
