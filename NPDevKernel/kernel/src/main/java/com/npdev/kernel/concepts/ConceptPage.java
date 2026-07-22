package com.npdev.kernel.concepts;

import java.util.List;

/**
 * LNCH-5: one page of a {@link ConceptQuery} result. {@code items} is the narrowed, sorted, windowed
 * slice; {@code total} is the count of all rows matching the filter (before the page window), so a
 * grid can render a pager; {@code hasMore} is true when rows beyond this window remain. Adapters that
 * push the window to SQL compute {@code total} with a matching {@code COUNT(*)} rather than by
 * materializing every row.
 */
public record ConceptPage(List<ConceptRecord> items, long total, boolean hasMore) {
    public ConceptPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (total < 0) {
            total = items.size();
        }
    }

    public static ConceptPage of(List<ConceptRecord> items, long total, int offset) {
        long windowEnd = (long) offset + (items == null ? 0 : items.size());
        return new ConceptPage(items, total, windowEnd < total);
    }
}
