package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B8 (Wave 2 package 2.1, docs/ACCEPTED_BOUNDARIES.md): explicit, operator-driven adoption of
 * ownership for a legacy database's tables -- the second half of the B8 lift. {@link
 * SchemaLifecycleExecutor#recordOwnershipForLiveManifestTables} closes the gap for every table NPDev
 * creates from now on; this closes it for a table that already existed before this build's
 * ownership-recording ever ran (a genuinely legacy database, or one that skipped the one intervening
 * successful boot the old workaround required).
 *
 * <p>Adoption is never inferred. A candidate table is only ever adopted when its LIVE shape matches
 * the manifest's declared shape with ZERO diff -- the same {@link SchemaDiffEngine} the boot-time
 * {@code classify()} path uses, but run UNSCOPED (against the table's true live shape, not scoped to
 * already-owned tables the way {@code classify()} scopes it -- scoping there exists to keep a
 * hand-created table out of the destructive path; here it would defeat the point, since the whole
 * question is whether an as-yet-unowned live table happens to match). A hand-created table with the
 * same name but a different shape is reported under {@link Preview#notAdoptable()}, never adopted.
 * Even a coincidental exact-shape match is safe to adopt: once recorded, the table only ever leaves
 * the owned set by falling out of {@code ownedTablesJson}'s live-table intersection (i.e. by being
 * dropped), and it is only ever ACTED on destructively when the model no longer declares it -- an
 * operator decision, not something this method causes by itself.
 */
public final class OwnershipAdoption {

    private OwnershipAdoption() {
    }

    /** @param table lower-cased table name
     *  @param mismatchReasons empty when the table matches the manifest exactly (adoptable); otherwise
     *      one diff-item key per difference found, same {@code itemKey} format the impact report uses */
    public record Candidate(String table, List<String> mismatchReasons) {
    }

    public record Preview(List<String> alreadyOwned, List<Candidate> adoptable, List<Candidate> notAdoptable) {
    }

    public record Result(List<String> adopted, List<Candidate> rejected) {
    }

    /** Dry-run: classifies every manifest-declared, currently-live, not-yet-owned table. Writes nothing. */
    public static Preview preview(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        return computeCandidates(dataSource, manifest);
    }

    /**
     * Re-verifies the SAME exact-match condition {@link #preview} would report -- never trusts a
     * caller's earlier preview, since the live schema can change between the two calls -- and adopts
     * only the candidates that still match exactly. A candidate that no longer matches is returned
     * under {@link Result#rejected()}, not silently skipped.
     */
    public static Result apply(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Preview preview = computeCandidates(dataSource, manifest);
        Set<String> toAdopt = new LinkedHashSet<>();
        for (Candidate candidate : preview.adoptable()) {
            toAdopt.add(candidate.table());
        }
        SchemaLifecycleExecutor.adoptOwnedBusinessTables(dataSource, toAdopt);
        return new Result(new ArrayList<>(toAdopt), preview.notAdoptable());
    }

    private static Preview computeCandidates(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Set<String> alreadyOwned = SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource);
        if (alreadyOwned == null) {
            alreadyOwned = Set.of();
        }
        Set<String> liveTables = SchemaLifecycleExecutor.readLiveTableNames(dataSource);

        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest), current);
        Map<String, List<SchemaDiffItem>> itemsByTable = diff.items().stream()
                .filter(item -> item.table() != null)
                .collect(Collectors.groupingBy(SchemaDiffItem::table));

        List<String> alreadyOwnedOut = new ArrayList<>();
        List<Candidate> adoptable = new ArrayList<>();
        List<Candidate> notAdoptable = new ArrayList<>();
        for (String table : manifest.businessTableColumns().keySet()) {
            String lower = table.toLowerCase(Locale.ROOT);
            if (alreadyOwned.contains(lower)) {
                alreadyOwnedOut.add(lower);
                continue;
            }
            if (!liveTables.contains(lower)) {
                continue; // declared but never created -- nothing live to adopt
            }
            List<SchemaDiffItem> items = itemsByTable.getOrDefault(lower, List.of());
            if (items.isEmpty()) {
                adoptable.add(new Candidate(lower, List.of()));
            } else {
                notAdoptable.add(new Candidate(lower, items.stream().map(SchemaDiffItem::itemKey).toList()));
            }
        }
        Collections.sort(alreadyOwnedOut);
        return new Preview(alreadyOwnedOut, adoptable, notAdoptable);
    }
}
