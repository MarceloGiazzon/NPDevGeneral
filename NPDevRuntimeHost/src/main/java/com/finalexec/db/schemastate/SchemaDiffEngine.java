package com.finalexec.db.schemastate;

import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The pure diff function (schema-engine rebuild, Phase 2): compares a {@link DesiredSchema} against a
 * {@link CurrentSchema} and produces the single {@link SchemaDiff} every downstream surface consumes.
 * No DB, no side effects, wired nowhere until Phase 4.
 *
 * <p><b>Rename resolution runs FIRST</b> — a declared rename paired old→new before classifying, because
 * a rename misread as drop-plus-add destroys the old column's data (the highest-stakes correctness rule
 * in the programme). Destructive items reuse {@code SchemaDeltaItem.stableString()} so acknowledgment
 * tokens stay byte-identical. FK/index diffing is deferred (P0.2 asymmetry: the desired side has no
 * explicit FK/index lists yet).
 */
public final class SchemaDiffEngine {

    public SchemaDiff diff(DesiredSchema desired, CurrentSchema current) {
        List<SchemaDiffItem> items = new ArrayList<>();
        Set<String> handledCurrentTables = new LinkedHashSet<>();
        Set<String> handledDesiredTables = new LinkedHashSet<>();

        // 1. Table renames first: a desired table declaring renamedFromTable that the live DB still has
        //    under the old name (and not the new) is an in-place rename, never drop+add.
        for (DesiredTable dt : desired.tables().values()) {
            String old = dt.renamedFromTable();
            if (old != null && current.tables().containsKey(old) && !current.tables().containsKey(dt.name())) {
                items.add(SchemaDiffItem.of("RENAME_TABLE:" + old + ":" + dt.name(), dt.name(), null,
                        SafetyClass.SAFE_RENAME, old, dt.name()));
                diffColumns(dt, current.tables().get(old), items);
                handledCurrentTables.add(old);
                handledDesiredTables.add(dt.name());
            }
        }

        // 2. Remaining desired tables: present live → diff columns; absent → SAFE_TABLE_CREATE.
        for (DesiredTable dt : desired.tables().values()) {
            if (handledDesiredTables.contains(dt.name())) {
                continue;
            }
            CurrentTable ct = current.tables().get(dt.name());
            if (ct != null) {
                diffColumns(dt, ct, items);
                handledCurrentTables.add(dt.name());
            } else {
                items.add(SchemaDiffItem.of("CREATE_TABLE:" + dt.name(), dt.name(), null,
                        SafetyClass.SAFE_TABLE_CREATE, null, dt.name()));
            }
        }

        // 3. Dropped tables: live tables the model no longer declares and that weren't a rename source.
        for (CurrentTable ct : current.tables().values()) {
            if (handledCurrentTables.contains(ct.name()) || desired.tables().containsKey(ct.name())) {
                continue;
            }
            String key = new SchemaDeltaItem.DropTable(ct.name(), 0L).stableString();
            items.add(SchemaDiffItem.of(key, ct.name(), null, SafetyClass.DESTRUCTIVE_DROP_TABLE, ct.name(), null));
        }

        items.sort(Comparator.comparing(SchemaDiffItem::table, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(i -> i.column() == null ? "" : i.column())
                .thenComparing(SchemaDiffItem::itemKey));
        return new SchemaDiff(List.copyOf(items));
    }

    private void diffColumns(DesiredTable dt, CurrentTable ct, List<SchemaDiffItem> items) {
        Set<String> handledCurrentCols = new LinkedHashSet<>();
        Set<String> handledDesiredCols = new LinkedHashSet<>();

        // 1. Column renames first.
        for (DesiredColumn dc : dt.columns().values()) {
            String old = dc.renamedFromColumn();
            if (old != null && ct.columns().containsKey(old) && !ct.columns().containsKey(dc.name())) {
                items.add(SchemaDiffItem.of("RENAME_COLUMN:" + dt.name() + ":" + old + ":" + dc.name(),
                        dt.name(), dc.name(), SafetyClass.SAFE_RENAME, old, dc.name()));
                compareColumn(dt, dc, ct.columns().get(old), items);
                handledCurrentCols.add(old);
                handledDesiredCols.add(dc.name());
            }
        }

        // 2. Desired columns: present → compare; absent → add (additive / backfill / hook).
        for (DesiredColumn dc : dt.columns().values()) {
            if (handledDesiredCols.contains(dc.name())) {
                continue;
            }
            CurrentColumn cc = ct.columns().get(dc.name());
            if (cc != null) {
                compareColumn(dt, dc, cc, items);
                handledCurrentCols.add(dc.name());
            } else {
                items.add(addColumnItem(dt, dc));
            }
        }

        // 3. Dropped columns.
        for (CurrentColumn cc : ct.columns().values()) {
            if (handledCurrentCols.contains(cc.name()) || dt.columns().containsKey(cc.name())) {
                continue;
            }
            String key = new SchemaDeltaItem.DropColumn(dt.name(), cc.name(), cc.normalizedSqlType()).stableString();
            items.add(SchemaDiffItem.of(key, dt.name(), cc.name(), SafetyClass.DESTRUCTIVE_DROP_COLUMN,
                    cc.normalizedSqlType(), null));
        }
    }

    private static SchemaDiffItem addColumnItem(DesiredTable dt, DesiredColumn dc) {
        if (dc.requiredByModel() && !dc.nullable()) {
            SafetyClass sc = dc.literalDefault() != null ? SafetyClass.NEEDS_BACKFILL : SafetyClass.NEEDS_HOOK;
            return SchemaDiffItem.of("ADD_REQUIRED_COLUMN:" + dt.name() + ":" + dc.name(), dt.name(), dc.name(),
                    sc, null, dc.normalizedSqlType());
        }
        return SchemaDiffItem.of("ADD_COLUMN:" + dt.name() + ":" + dc.name(), dt.name(), dc.name(),
                SafetyClass.SAFE_ADDITIVE, null, dc.normalizedSqlType());
    }

    private void compareColumn(DesiredTable dt, DesiredColumn dc, CurrentColumn cc, List<SchemaDiffItem> items) {
        // Type change.
        String from = cc.normalizedSqlType();
        String to = dc.normalizedSqlType();
        if (from != null && to != null && !normalize(from).equals(normalize(to))) {
            if (TypeChangeMatrix.classify(from, to) == TypeChangeMatrix.Classification.WIDENING) {
                items.add(SchemaDiffItem.of("WIDEN_TYPE:" + dt.name() + ":" + dc.name() + ":" + from + ":" + to,
                        dt.name(), dc.name(), SafetyClass.SAFE_WIDEN, from, to));
            } else {
                String key = new SchemaDeltaItem.NarrowType(dt.name(), dc.name(), from, to).stableString();
                items.add(SchemaDiffItem.of(key, dt.name(), dc.name(), SafetyClass.DESTRUCTIVE_NARROW_TYPE, from, to));
            }
        }

        // Nullability change.
        if (dc.nullable() != cc.nullable()) {
            if (dc.nullable()) {
                // required -> optional: relaxing NOT NULL never loses data.
                items.add(SchemaDiffItem.of("RELAX_NOT_NULL:" + dt.name() + ":" + dc.name(), dt.name(), dc.name(),
                        SafetyClass.SAFE_RELAX, "NULL", "NOT NULL"));
            } else if (dc.platformManaged()) {
                // A loosened platform column is repaired (backfill to default, restore NOT NULL) — its own
                // tighten item, never SAFE_RELAX (§6). Auto-resolved, so NEEDS_BACKFILL.
                items.add(SchemaDiffItem.of("TIGHTEN_PLATFORM:" + dt.name() + ":" + dc.name(), dt.name(), dc.name(),
                        SafetyClass.NEEDS_BACKFILL, "NOT NULL", "NULL"));
            } else {
                // A model field made required: backfill from literal default, else an operator hook.
                SafetyClass sc = dc.literalDefault() != null ? SafetyClass.NEEDS_BACKFILL : SafetyClass.NEEDS_HOOK;
                items.add(SchemaDiffItem.of("TIGHTEN_NOT_NULL:" + dt.name() + ":" + dc.name(), dt.name(), dc.name(),
                        sc, "NOT NULL", "NULL"));
            }
        }
    }

    private static String normalize(String sqlType) {
        String normalized = SqlTypeNormalization.normalize(sqlType);
        return normalized == null ? Objects.toString(sqlType, "") : normalized;
    }
}
