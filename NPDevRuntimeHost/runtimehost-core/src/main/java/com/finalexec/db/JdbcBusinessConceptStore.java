package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.kernel.concepts.ConceptAggregateEngine;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.storage.sql.SqlType;
import com.npdev.kernel.storage.sql.UpsertPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JdbcBusinessConceptStore implements ConceptStore {
    /** The pushdown claim -- WHERE/LIMIT/GROUP BY compiled to real SQL rather than filtered in the
     * JVM -- is only provable from the emitted statement text, not from a returned row count (a
     * JVM-side filter returns the right count too). DEBUG-only so it costs nothing when disabled;
     * enable with {@code -Dlogging.level.com.finalexec.db.JdbcBusinessConceptStore=DEBUG}. */
    private static final Logger LOG = LoggerFactory.getLogger(JdbcBusinessConceptStore.class);

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final Map<String, ConceptShape> shapesByConcept;
    private final Map<String, TableColumns> tableColumnsCache = new ConcurrentHashMap<>();

    public JdbcBusinessConceptStore(DataSource dataSource, CompiledModel compiledModel) {
        this(dataSource, compiledModel, SqlDialects.active());
    }

    /** Explicit dialect, for the conformance suite and for a host that pins its engine at boot. */
    public JdbcBusinessConceptStore(DataSource dataSource, CompiledModel compiledModel, SqlDialect dialect) {
        this.dataSource = dataSource;
        this.shapesByConcept = shapes(compiledModel);
        this.dialect = java.util.Objects.requireNonNull(dialect, "dialect");
    }

    /**
     * npdev-sql-identifier-quoting: one of THREE seams that turn a model name into SQL identifier
     * text (STOR-6). All three must ask SqlDialect.identifier(); quoting one alone leaves an app
     * that builds, boots, and cannot find its own table. Twin-pair rule:
     * sql-identifier-quoting-three-seams.
     *
     * <p>A business identifier ready to embed in SQL, quoted only if this engine reserves it.
     *
     * <p><b>This must apply the IDENTICAL rule to {@code SchemaRealizationEmitter.sqlId}.</b> The
     * emitter creates the table; this queries it. A quoted table queried unquoted -- or the reverse
     * -- is the twin-pair break in its worst form: the app builds, boots, and cannot find its own
     * tables. {@code SqlNamingSupport} states the constraint plainly ("the runtime side must query
     * the exact same table/column names Flyway/the generator emit"), and
     * {@code twin-pair-registry.json} pins the two so an edit to one fails the gate.
     *
     * <p>Raw everywhere else. These names are also map keys and catalog lookups, where the UNQUOTED
     * form is what the database stores and what {@code TableColumns} compares against.
     */
    private String sqlId(String rawIdentifier) {
        return dialect.identifier(rawIdentifier);
    }

    /**
     * LNCH-17: {@code dataSource.getConnection()} would hand back a brand-new physical
     * connection, entirely independent of whatever Spring-managed transaction the calling
     * generated-service method is running under (e.g. {@code @Transactional public ... create(...)}
     * in service-base.mustache, which also does a separate JPA {@code persistence.save(entity)}
     * in the same method) -- so a concept write through this store and that JPA write were two
     * uncoordinated auto-commits, not one atomic unit: if the JPA write failed afterward, the
     * kernel-gateway write already landed and stayed landed. {@link DataSourceUtils#getConnection}
     * joins the ambient Spring transaction when one is active (same mechanism {@code JdbcTemplate}
     * uses internally) and transparently falls back to a plain new connection when there isn't
     * one, so every existing non-transactional caller (tests, non-Spring contexts) is unaffected.
     */
    private Connection openConnection() {
        return DataSourceUtils.getConnection(dataSource);
    }

    /** Releases a connection opened via {@link #openConnection()} -- a no-op close if it's bound
     * to the ambient transaction (the transaction manager closes it when the transaction ends),
     * a real close otherwise. Must be called from a {@code finally}, not try-with-resources,
     * since try-with-resources would call {@code Connection#close()} directly and defeat this. */
    private void releaseConnection(Connection connection) {
        DataSourceUtils.releaseConnection(connection, dataSource);
    }

    @Override
    public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        String sql = "SELECT * FROM " + sqlId(shape.tableName()) + " WHERE " + sqlId(shape.idColumn()) + " = ? AND tenant_id = ?"
                + deletedAtFilter(shape);
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, coerceId(id));
            bindObject(statement, 2, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(toRecord(shape, tenantId, resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /**
     * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): identical to {@link #findById}, except
     * {@code FOR UPDATE} -- locks the row against a concurrent {@code findByIdForUpdate} for the
     * remainder of the ambient transaction (see {@link #openConnection}'s {@code DataSourceUtils}
     * join), closing the read-then-persist race window a plain {@code findById} left open. Only
     * locks anything real when this call is actually inside a transaction (an unmanaged connection's
     * own implicit auto-commit releases the lock the instant this statement finishes) -- callers
     * relying on the lock surviving past this single read must be running inside one (see
     * {@code DefaultConceptGateway}'s {@code TransactionRunner}).
     */
    @Override
    public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        String sql = com.npdev.kernel.storage.sql.SqlDialects.active().selectForUpdate(
                "*", sqlId(shape.tableName()), sqlId(shape.idColumn()) + " = ? AND tenant_id = ?" + deletedAtFilter(shape));
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, coerceId(id));
            bindObject(statement, 2, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(toRecord(shape, tenantId, resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading (for update) concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        ConceptShape shape = shape(conceptName);
        String sql = "SELECT * FROM " + sqlId(shape.tableName()) + " WHERE tenant_id = ?" + deletedAtFilter(shape)
                + " ORDER BY " + sqlId(shape.idColumn());
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ConceptRecord> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(toRecord(shape, tenantId, resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /**
     * RUN-1 (R8a): the pushdown counterpart of {@link ConceptStore#findAllCapped}'s interface
     * default -- fetches at most {@code maxRows + 1} rows (one more than the cap, cheaply, in the
     * SAME query) so the database itself never streams a whole tenant table for this call, and the
     * "+1" is all that's needed to tell "truncated" from "exactly maxRows rows total" without a
     * separate {@code COUNT(*)}. Same stable {@code ORDER BY} as {@link #findAll} so which rows get
     * cut is deterministic across calls.
     */
    @Override
    public ConceptListSlice<ConceptRecord> findAllCapped(String tenantId, String conceptName, int maxRows) {
        ConceptShape shape = shape(conceptName);
        String sql = dialect.paginated("SELECT * FROM " + sqlId(shape.tableName())
                + " WHERE tenant_id = ?" + deletedAtFilter(shape) + " ORDER BY " + sqlId(shape.idColumn()) + " ").stripTrailing();
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int nextIndex = 1;
            bindObject(statement, nextIndex++, tenantId);
            for (int pageValue : dialect.limitOffset().values(maxRows + 1, 0)) {
                statement.setInt(nextIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ConceptRecord> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(toRecord(shape, tenantId, resultSet));
                }
                boolean truncated = out.size() > maxRows;
                List<ConceptRecord> bounded = truncated ? List.copyOf(out.subList(0, maxRows)) : List.copyOf(out);
                return new ConceptListSlice<>(bounded, truncated);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing (capped) concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /**
     * R5.2 (closes RUN-1 item 4): the pushdown counterpart of {@link ConceptStore#existsUnique}'s
     * interface default. Before this override, EVERY create/update of a concept with a {@code
     * unique: true} (or compound-unique) invariant called {@link #findAll} first -- loading and
     * deserializing the tenant's ENTIRE table into the JVM just to answer one yes/no question, the
     * platform's worst remaining data-scale landmine.
     *
     * <p><b>How this stays byte-for-byte identical to the old full scan.</b> Per-field, the {@code
     * WHERE} predicate below is deliberately a SUPERSET of {@link ConceptStore#uniqueValuesCollide},
     * not an attempt to replicate it exactly in SQL text -- doing the comparison in SQL only ever
     * needs to be at least as inclusive as the JVM rule, never bit-exact, because every row the query
     * returns is re-checked against the real {@link ConceptStore#uniqueValuesCollide} below before it
     * can flip the answer to {@code true}. That is what makes an engine-formatting wrinkle (a {@code
     * DECIMAL} column's scale, a driver's date-to-text rendering, MySQL's {@code CAST} rejecting
     * {@code VARCHAR}) harmless instead of a silent correctness bug: the SQL side can only ever be
     * WRONG in the direction of "too many candidates", and the JVM re-check throws every false
     * positive away. Concretely:
     * <ul>
     *   <li>A text-shaped column (string/enum, and anything {@link SqlTypeSupport}'s own default
     *       maps to {@code VARCHAR}) compares
     *       {@code LOWER(dialect.trimmedText(dialect.cast(column, TEXT)))} against the incoming
     *       value's own {@code String.valueOf(...).trim().toLowerCase()} -- for a text column the
     *       cast is an identity, so it is exact, not just a superset, and reuses the column's own
     *       pre-existing DB unique index only when the value also happens to already be stored in
     *       that exact case/trim -- which is the common case, and the reason a real duplicate-field
     *       create is fast even before any future functional index exists. {@code trimmedText} (not
     *       a bare {@code TRIM(...)}) because single-argument {@code TRIM} did not exist in T-SQL
     *       before SQL Server 2017 -- see that method's javadoc.</li>
     *   <li>A non-text column (numeric/boolean/date/datetime/reference) compares natively
     *       ({@code column = ?}, the incoming value coerced via {@link #coerceValue} exactly the way
     *       the write path already coerces it) -- native SQL equality on a fixed-scale/typed column
     *       is provably a superset of {@link ConceptStore#uniqueValuesCollide}'s "neither side is a
     *       String" branch (SQL's {@code 42 = 42.0000} is true; the JVM rule's
     *       {@code String.valueOf} comparison for that same pair is false), and this branch is a real
     *       indexed lookup TODAY because it reuses the plain unique index/constraint the schema
     *       emitter already creates for {@code unique: true} fields.</li>
     * </ul>
     *
     * <p>No {@code LIMIT} on the candidate query: the DB-level unique index is case-sensitive and
     * untrimmed (see {@code currentTenantId()}'s javadoc in service-base.mustache), so more than one
     * pre-existing row COULD differ only by case/whitespace and all of them must be considered, not
     * just the first one SQL happens to return. In practice this still means "zero rows, or a
     * small handful", never the whole table -- that is the measured fix.
     */
    @Override
    public boolean existsUnique(String tenantId, String conceptName, List<String> fieldNames, List<Object> values, String excludeId) {
        if (fieldNames == null || fieldNames.isEmpty() || values == null || fieldNames.size() != values.size()) {
            return false;
        }
        for (Object value : values) {
            if (value == null) {
                return false;
            }
        }
        ConceptShape shape = shape(conceptName);
        List<String> columns = new ArrayList<>();
        for (String fieldName : fieldNames) {
            columns.add(requireColumn(shape, fieldName));
        }

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add("tenant_id = ?");
        params.add(tenantId);
        // R5.4: "unique among live rows only" -- a soft-deleted row's value never blocks reuse, at
        // this JVM-side precheck layer. See StorageCapability#PARTIAL_UNIQUE_INDEX for the matching
        // DB-level enforcement (filtered index on Postgres/SQL Server; this precheck is the ONLY
        // enforcement on H2/MySQL, which have no partial-index feature).
        if (shape.softDelete()) {
            whereClauses.add("deleted_at IS NULL");
        }
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            Object value = values.get(i);
            String dslType = shape.dslTypeByColumn().get(column.toLowerCase(Locale.ROOT));
            if (isTextLikeDslType(dslType)) {
                whereClauses.add("LOWER(" + dialect.trimmedText(dialect.cast(sqlId(column), SqlType.TEXT)) + ") = ?");
                params.add(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
            } else {
                whereClauses.add(sqlId(column) + " = ?");
                params.add(coerceValue(column, value, dslType));
            }
        }
        if (excludeId != null) {
            whereClauses.add(sqlId(shape.idColumn()) + " <> ?");
            params.add(coerceId(excludeId));
        }
        List<String> selectColumns = new ArrayList<>();
        selectColumns.add(sqlId(shape.idColumn()));
        for (String column : columns) {
            selectColumns.add(sqlId(column));
        }
        String sql = "SELECT " + String.join(", ", selectColumns) + " FROM " + sqlId(shape.tableName())
                + " WHERE " + String.join(" AND ", whereClauses);

        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParams(statement, params, 1);
            SqlDialect connectionDialect = SqlDialects.forConnection(connection);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String candidateId = String.valueOf(connectionDialect.readValue(resultSet.getObject(1)));
                    if (excludeId != null && excludeId.equalsIgnoreCase(candidateId)) {
                        continue;
                    }
                    boolean allMatch = true;
                    for (int i = 0; i < fieldNames.size(); i++) {
                        Object existingValue = connectionDialect.readValue(resultSet.getObject(i + 2));
                        if (!ConceptStore.uniqueValuesCollide(existingValue, values.get(i))) {
                            allMatch = false;
                            break;
                        }
                    }
                    if (allMatch) {
                        return true;
                    }
                }
                return false;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed checking uniqueness for concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /**
     * R5.2: whether {@code dslType}'s stored column reads back as a Java {@link String} (so the
     * ORIGINAL {@code left instanceof String || right instanceof String} branch of {@link
     * ConceptStore#uniqueValuesCollide} would be taken purely because of the STORED side, regardless
     * of the incoming value's own type). Built as "known non-text types" rather than "known text
     * types" so it agrees with {@link SqlTypeSupport#sqlType}'s own {@code default -> VARCHAR} --
     * an unrecognized/future DSL type is text-shaped there, so it must be text-shaped here too.
     */
    private static boolean isTextLikeDslType(String dslType) {
        if (dslType == null || dslType.isBlank()) {
            return true;
        }
        String normalized = dslType.trim().toLowerCase(Locale.ROOT);
        if (isNumericDslType(normalized)) {
            return false;
        }
        return switch (normalized) {
            case "boolean", "date", "datetime", "reference", "uuid", "object", "array", "file", "json" -> false;
            default -> true;
        };
    }

    /**
     * LNCH-5: pushes the filter/sort/page window down to SQL. Column names are resolved through the
     * compiled model's field->column whitelist (never taken from raw input), and every filter value
     * is a bound parameter, so this is not a string-concatenation injection surface. {@code total} is
     * a matching {@code COUNT(*)} rather than a materialize-everything count, and {@code LIMIT}/{@code
     * OFFSET} keep the JVM from ever holding more than one page. A stable {@code ORDER BY} (the id
     * column when the caller declares no sort) makes OFFSET paging deterministic.
     *
     * <p>R4.3 (Roadmap Wave 1): the base table is now ALWAYS aliased ({@code npdev_base}), the same
     * convention {@link #aggregate} already established, so a {@link ConceptQuery.Filter} naming a
     * reference-path field (see that record's own javadoc) can be resolved via a real SQL
     * {@code JOIN} -- {@link #registerJoinChain}, reused rather than re-derived. When a predicate
     * needs no join the emitted SQL is behaviourally identical to before (an aliased single-table
     * {@code SELECT}, which is not a semantic change); the select list narrows from {@code *} to
     * {@code npdev_base.*} only when a join is actually present, so a join's columns never leak into
     * {@link #toRecord}'s column scan.
     */
    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        ConceptShape shape = shape(conceptName);
        ConceptQuery effective = query == null ? ConceptQuery.firstPage() : query;
        String baseAlias = "npdev_base";

        Map<String, JoinPlan> joinsByChainKey = new LinkedHashMap<>();
        List<String> joinClauses = new ArrayList<>();
        List<Object> joinParams = new ArrayList<>();

        List<String> whereClauses = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();
        whereClauses.add(baseAlias + ".tenant_id = ?");
        whereParams.add(tenantId);
        // R5.4: grids/pickers built on this method never see a soft-deleted row.
        if (shape.softDelete()) {
            whereClauses.add(baseAlias + ".deleted_at IS NULL");
        }
        String predicateSql = renderPredicateSql(shape, baseAlias, effective.filters(),
                joinsByChainKey, joinClauses, joinParams, whereParams, tenantId);
        if (predicateSql != null) {
            whereClauses.add(predicateSql);
        }
        String whereSql = String.join(" AND ", whereClauses);
        String orderSql = orderByClause(shape, baseAlias, effective.sorts());

        String joinSql = String.join("", joinClauses);
        String fromSql = sqlId(shape.tableName()) + " AS " + baseAlias + joinSql;
        String selectList = joinClauses.isEmpty() ? "*" : baseAlias + ".*";

        List<Object> params = new ArrayList<>(joinParams);
        params.addAll(whereParams);

        Connection connection = openConnection();
        try {
            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + fromSql + " WHERE " + whereSql)) {
                bindParams(statement, params, 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    total = resultSet.getLong(1);
                }
            }

            List<ConceptRecord> items = new ArrayList<>();
            String pageSql = dialect.paginated("SELECT " + selectList + " FROM " + fromSql + " WHERE " + whereSql
                    + orderSql + " ").stripTrailing();
            LOG.debug("npdev.query.sql concept={} sql={} limit={} offset={}",
                    conceptName, pageSql, effective.limit(), effective.offset());
            try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                int nextIndex = bindParams(statement, params, 1);
                for (int pageValue : dialect.limitOffset().values(effective.limit(), effective.offset())) {
                    statement.setInt(nextIndex++, pageValue);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        items.add(toRecord(shape, tenantId, resultSet));
                    }
                }
            }
            return ConceptPage.of(items, total, effective.offset());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /**
     * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): compiles a {@link ConceptAggregateQuery}
     * to a real, parameterized SQL {@code GROUP BY} -- confirmed from the SQL log, not inferred
     * (the plan's own DoD line). Every identifier in the generated SQL comes from
     * {@link #requireColumn}, which only ever returns a column this concept's compiled shape
     * declares -- never a caller-supplied string concatenated directly, the same discipline
     * {@link #query} already follows for {@code WHERE}/{@code ORDER BY}.
     *
     * <p>A bucketed {@code groupBy} field is truncated in SQL via {@code DATE_TRUNC(unit, column)}
     * (portable across H2 2.x and Postgres) and the truncated value is read back and formatted to
     * this platform's own bucket-label convention via {@link ConceptAggregateEngine#bucketLabel} --
     * the SAME formatting the in-memory adapter applies to a raw (untruncated) value -- so the two
     * engines produce byte-identical group labels despite one truncating in SQL and the other in
     * Java. {@code HAVING}/sort/limit are deliberately NOT pushed to SQL; see
     * {@link ConceptAggregateEngine#applyHavingSortAndLimit} for why applying them once, in Java,
     * over the (small) already-grouped result is the correct v1 simplification, not a shortcut.
     *
     * <p>S4 (roadmap B27, ADR-0011 D1): a {@code groupBy} field may be a join
     * ({@link GroupByJoinGrammar}) -- the base table is now ALWAYS aliased ({@code npdev_base}),
     * every base-table column reference prefixed with it, and each distinct joined reference-field
     * HOP gets exactly one {@code JOIN <targetTable> AS npdev_joinN ON <prevAlias>.<fk> =
     * npdev_joinN.<id> AND npdev_joinN.tenant_id = ?}. INNER JOIN, deliberately: a base row whose
     * reference field is null (or points at a row this tenant doesn't own) cannot contribute a
     * joined group value and is excluded from the aggregate -- {@code InMemoryConceptStore}'s own
     * join pre-materialization mirrors this exactly (excludes the same rows) so both engines agree
     * on the same query. {@code where}/{@code aggregates} stay base-concept-only (unchanged scope;
     * only {@code groupBy} gets the join grammar, per the roadmap's own reference shape).
     *
     * <p>S8 W1.1 (roadmap deferred item #1): the join may chain up to
     * {@link GroupByJoinGrammar#MAX_JOIN_HOPS} hops -- {@link #registerJoinChain} walks the chain one
     * hop at a time, keying each hop's {@code JoinPlan} by its PREFIX of the chain (e.g. {@code
     * "shipment."} then {@code "shipment.invoice."}) so two {@code groupBy} entries sharing a prefix
     * (one 1-hop, one 2-hop through the same first hop) reuse the same {@code JOIN} for that shared
     * prefix rather than joining the same table twice.
     */
    @Override
    public ConceptAggregateResult aggregate(String tenantId, String conceptName, ConceptAggregateQuery query) {
        ConceptShape shape = shape(conceptName);
        String baseAlias = "npdev_base";

        List<GroupByJoinGrammar.Target> parsedGroupBy = new ArrayList<>();
        for (ConceptAggregateQuery.GroupByField groupByField : query.groupBy()) {
            parsedGroupBy.add(GroupByJoinGrammar.parse(groupByField.field()));
        }

        Map<String, JoinPlan> joinsByChainKey = new LinkedHashMap<>();
        List<String> joinClauses = new ArrayList<>();
        List<Object> joinParams = new ArrayList<>();
        for (GroupByJoinGrammar.Target target : parsedGroupBy) {
            if (target instanceof GroupByJoinGrammar.Target.Join join) {
                registerJoinChain(shape, join.referenceFields(), baseAlias, joinsByChainKey, joinClauses, joinParams, tenantId);
            }
        }

        List<String> whereClauses = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();
        whereClauses.add(baseAlias + ".tenant_id = ?");
        whereParams.add(tenantId);
        // R5.4: same base-concept exclusion as query() above -- a soft-deleted base row never
        // contributes to an aggregate. (A joined TARGET row's own soft-delete state is not filtered
        // here -- out of this round's scope, see the ledger's "deliberately not done" section.)
        if (shape.softDelete()) {
            whereClauses.add(baseAlias + ".deleted_at IS NULL");
        }
        // R4.3: shared with query() -- reuses the SAME joinsByChainKey/joinClauses/joinParams the
        // groupBy loop above already populated, so a WHERE-clause reference-path join and a groupBy
        // join to the same chain are deduplicated into one SQL JOIN rather than joining twice.
        String predicateSql = renderPredicateSql(shape, baseAlias, query.filters(),
                joinsByChainKey, joinClauses, joinParams, whereParams, tenantId);
        if (predicateSql != null) {
            whereClauses.add(predicateSql);
        }
        String whereSql = String.join(" AND ", whereClauses);

        List<String> groupByExprs = new ArrayList<>();
        List<String> groupByAliases = new ArrayList<>();
        for (int i = 0; i < query.groupBy().size(); i++) {
            ConceptAggregateQuery.GroupByField groupByField = query.groupBy().get(i);
            String columnRef;
            GroupByJoinGrammar.Target target = parsedGroupBy.get(i);
            if (target instanceof GroupByJoinGrammar.Target.Join join) {
                JoinPlan plan = joinsByChainKey.get(chainKey(join.referenceFields()));
                columnRef = plan.alias() + "." + requireColumn(plan.targetShape(), join.targetField());
            } else {
                String field = ((GroupByJoinGrammar.Target.Direct) target).field();
                columnRef = baseAlias + "." + requireColumn(shape, field);
            }
            String alias = "npdev_g" + groupByAliases.size();
            if (groupByField.bucket() != null && !groupByField.bucket().isBlank()) {
                groupByExprs.add("DATE_TRUNC('" + sqlBucketUnit(groupByField.bucket()) + "', " + columnRef + ")");
            } else {
                groupByExprs.add(columnRef);
            }
            groupByAliases.add(alias);
        }

        List<String> aggregateExprs = new ArrayList<>();
        List<String> aggregateAliases = new ArrayList<>();
        for (ConceptAggregateQuery.AggregateFunction aggregate : query.aggregates()) {
            String alias = "npdev_a" + aggregateAliases.size();
            String fn = aggregate.fn() == null ? "" : aggregate.fn().trim().toLowerCase(Locale.ROOT);
            String expr = switch (fn) {
                case "count" -> "COUNT(*)";
                case "sum" -> "SUM(" + baseAlias + "." + requireColumn(shape, aggregate.field()) + ")";
                case "avg" -> "AVG(" + baseAlias + "." + requireColumn(shape, aggregate.field()) + ")";
                case "min" -> "MIN(" + baseAlias + "." + requireColumn(shape, aggregate.field()) + ")";
                case "max" -> "MAX(" + baseAlias + "." + requireColumn(shape, aggregate.field()) + ")";
                default -> throw new IllegalArgumentException("Unsupported aggregate fn: " + aggregate.fn());
            };
            aggregateExprs.add(expr);
            aggregateAliases.add(alias);
        }

        List<String> selectItems = new ArrayList<>();
        for (int i = 0; i < groupByExprs.size(); i++) {
            selectItems.add(groupByExprs.get(i) + " AS " + groupByAliases.get(i));
        }
        for (int i = 0; i < aggregateExprs.size(); i++) {
            selectItems.add(aggregateExprs.get(i) + " AS " + aggregateAliases.get(i));
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectItems))
                .append(" FROM ").append(sqlId(shape.tableName())).append(" AS ").append(baseAlias);
        for (String joinClause : joinClauses) {
            sql.append(joinClause);
        }
        sql.append(" WHERE ").append(whereSql);
        if (!groupByExprs.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByExprs));
        }
        LOG.debug("npdev.aggregate.sql concept={} sql={}", conceptName, sql);

        // JOIN ... ON's own "?" placeholders appear in the SQL text before WHERE's, so their bound
        // values must come first too -- PreparedStatement binds by textual position, not by name.
        List<Object> params = new ArrayList<>(joinParams);
        params.addAll(whereParams);

        List<Map<String, Object>> rows = new ArrayList<>();
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParams(statement, params, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < query.groupBy().size(); i++) {
                        ConceptAggregateQuery.GroupByField groupByField = query.groupBy().get(i);
                        Object raw = resultSet.getObject(groupByAliases.get(i));
                        Object value = raw;
                        if (groupByField.bucket() != null && !groupByField.bucket().isBlank()) {
                            java.time.LocalDate date = ConceptAggregateEngine.toLocalDate(raw);
                            value = date == null ? null
                                    : ConceptAggregateEngine.bucketLabel(date, groupByField.bucket());
                        }
                        row.put(groupByField.field(), value);
                    }
                    for (int i = 0; i < query.aggregates().size(); i++) {
                        row.put(query.aggregates().get(i).outputName(), resultSet.getObject(aggregateAliases.get(i)));
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed aggregating concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }

        rows = ConceptAggregateEngine.applyHavingSortAndLimit(rows, query.having(), query.sorts(), query.limit());
        return new ConceptAggregateResult(rows);
    }

    /** S4 + S8 W1.1: one distinct joined reference-field HOP gets exactly one JOIN clause, keyed by
     *  its chain PREFIX ({@link #chainKey}) so a query with multiple {@code groupBy} entries sharing
     *  a prefix (one 1-hop, one 2-hop through the same first hop; or one plain, one bucketed through
     *  an identical chain) reuses it rather than joining the same table twice. */
    private record JoinPlan(String alias, ConceptShape targetShape) {
    }

    /** S8 W1.1: the dedup key for a join chain PREFIX -- {@code ["shipment"]} and
     *  {@code ["shipment", "invoice"]} produce {@code "shipment."} and {@code "shipment.invoice."}
     *  respectively, so the second reuses the first hop's JOIN and only adds one more. */
    private static String chainKey(List<String> referenceFields) {
        StringBuilder key = new StringBuilder();
        for (String referenceField : referenceFields) {
            key.append(normalize(referenceField)).append('.');
        }
        return key.toString();
    }

    /** S8 W1.1: walks {@code referenceFieldChain} one hop at a time, emitting/reusing one JOIN
     *  clause per distinct chain prefix, and returns the {@link JoinPlan} for the FULL chain (the
     *  concept {@code targetField} is read from). */
    private JoinPlan registerJoinChain(
            ConceptShape baseShape,
            List<String> referenceFieldChain,
            String baseAlias,
            Map<String, JoinPlan> joinsByChainKey,
            List<String> joinClauses,
            List<Object> joinParams,
            String tenantId
    ) {
        ConceptShape currentShape = baseShape;
        String currentAlias = baseAlias;
        StringBuilder prefix = new StringBuilder();
        JoinPlan plan = null;
        for (String referenceField : referenceFieldChain) {
            prefix.append(normalize(referenceField)).append('.');
            String key = prefix.toString();
            JoinPlan existing = joinsByChainKey.get(key);
            if (existing != null) {
                plan = existing;
                currentShape = existing.targetShape();
                currentAlias = existing.alias();
                continue;
            }
            String targetConceptName = currentShape.referenceTargetByField().get(normalize(referenceField));
            if (targetConceptName == null || targetConceptName.isBlank()) {
                throw new IllegalArgumentException(
                        "groupBy join field '" + referenceField + "' on concept " + currentShape.conceptName()
                                + " is not a declared reference field -- the compile-time validator "
                                + "(PackValidation#validateAggregateQuery) should have refused this model "
                                + "before it ever reached the store");
            }
            ConceptShape targetShape = shape(targetConceptName);
            String fkColumn = requireColumn(currentShape, referenceField);
            String alias = "npdev_join" + joinsByChainKey.size();
            joinClauses.add(" JOIN " + targetShape.tableName() + " AS " + alias
                    + " ON " + currentAlias + "." + fkColumn + " = " + alias + "." + targetShape.idColumn()
                    + " AND " + alias + ".tenant_id = ?");
            joinParams.add(tenantId);
            plan = new JoinPlan(alias, targetShape);
            joinsByChainKey.put(key, plan);
            currentShape = targetShape;
            currentAlias = alias;
        }
        return plan;
    }

    /** Move 10 B1: the closed {@code groupBy[].bucket} vocabulary maps 1:1 onto {@code DATE_TRUNC}'s
     *  own unit literals on both H2 2.x and Postgres -- no translation needed beyond validating the
     *  value came from the compiled model's own closed enum (already enforced at compile time by
     *  {@code PackValidation#validateAggregateQuery}; re-checked here so this method can never emit
     *  an arbitrary caller-supplied string into the SQL text). */
    private static String sqlBucketUnit(String bucket) {
        String normalized = bucket.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "day", "week", "month", "quarter", "year" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported groupBy bucket: " + bucket);
        };
    }

    private String requireColumn(ConceptShape shape, String field) {
        String column = shape.columnByField().get(field.toLowerCase(Locale.ROOT));
        if (column == null && shape.idColumn().equalsIgnoreCase(toDbColumn(field))) {
            column = shape.idColumn();
        }
        if (column == null) {
            throw new IllegalArgumentException(
                    "Unknown query field '" + field + "' for concept " + shape.conceptName()
                            + " -- only declared fields may be filtered or sorted");
        }
        return column;
    }

    private String orderByClause(ConceptShape shape, String baseAlias, List<ConceptQuery.Sort> sorts) {
        if (sorts.isEmpty()) {
            // OFFSET paging is only deterministic under a stable order; default to the primary key.
            return " ORDER BY " + baseAlias + "." + sqlId(shape.idColumn());
        }
        List<String> terms = new ArrayList<>();
        for (ConceptQuery.Sort sort : sorts) {
            String column = requireColumn(shape, sort.field());
            terms.add(baseAlias + "." + sqlId(column) + (sort.descending() ? " DESC" : " ASC"));
        }
        return " ORDER BY " + String.join(", ", terms);
    }

    private static String sqlOperator(ConceptQuery.Operator operator) {
        return switch (operator) {
            case EQ -> "=";
            case EQ_CI -> throw new IllegalStateException("EQ_CI is compiled to a case-insensitive text comparison, not a binary operator");
            case NEQ -> "<>";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case CONTAINS -> throw new IllegalStateException("CONTAINS is compiled to LIKE, not a binary operator");
            case STARTS_WITH -> throw new IllegalStateException("STARTS_WITH is compiled to LIKE, not a binary operator");
            case IN -> throw new IllegalStateException("IN is compiled to IN (...), not a binary operator");
            case IS_NULL -> throw new IllegalStateException("IS_NULL is compiled to a unary null-check, not a binary operator");
            case IS_NOT_NULL -> throw new IllegalStateException("IS_NOT_NULL is compiled to a unary null-check, not a binary operator");
            case OR_GROUPS -> throw new IllegalStateException("OR_GROUPS is a marker filter (nested groups), not a binary operator");
        };
    }

    /**
     * R4.3 (Roadmap Wave 1): renders {@code filters} into ONE WHERE fragment (no leading "WHERE",
     * no leading "AND") -- either the flat AND-list shape {@link ConceptQuery.Filter} has always
     * had, or the single {@link ConceptQuery.Operator#OR_GROUPS} marker shape (see that constant's
     * javadoc). Registers any reference-path JOIN a clause needs via {@link #registerJoinChain},
     * the SAME join machinery a {@code groupBy} path already uses -- keyed into the SAME
     * {@code joinsByChainKey} map the caller passes in, so a predicate join and a {@code groupBy}
     * join (or two predicate joins) to the same reference chain are deduplicated into one SQL
     * {@code JOIN} rather than joining the same table twice.
     *
     * @return the WHERE fragment, or {@code null} when {@code filters} is empty (caller adds nothing)
     */
    private String renderPredicateSql(
            ConceptShape baseShape, String baseAlias, List<ConceptQuery.Filter> filters,
            Map<String, JoinPlan> joinsByChainKey, List<String> joinClauses, List<Object> joinParams,
            List<Object> whereParams, String tenantId) {
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1 && filters.get(0).operator() == ConceptQuery.Operator.OR_GROUPS) {
            @SuppressWarnings("unchecked")
            List<List<ConceptQuery.Filter>> groups = (List<List<ConceptQuery.Filter>>) filters.get(0).value();
            List<String> groupSqls = new ArrayList<>();
            for (List<ConceptQuery.Filter> group : groups) {
                List<String> clauseSqls = new ArrayList<>();
                for (ConceptQuery.Filter clause : group) {
                    clauseSqls.add(renderPredicateClause(baseShape, baseAlias, clause,
                            joinsByChainKey, joinClauses, joinParams, whereParams, tenantId));
                }
                groupSqls.add("(" + String.join(" AND ", clauseSqls) + ")");
            }
            return "(" + String.join(" OR ", groupSqls) + ")";
        }
        List<String> clauseSqls = new ArrayList<>();
        for (ConceptQuery.Filter filter : filters) {
            clauseSqls.add(renderPredicateClause(baseShape, baseAlias, filter,
                    joinsByChainKey, joinClauses, joinParams, whereParams, tenantId));
        }
        return String.join(" AND ", clauseSqls);
    }

    /**
     * Renders ONE clause. {@code clause.field()} is resolved via {@link GroupByJoinGrammar#parse}
     * ONLY when it looks like a reference path (contains {@code .} or {@code ::}) -- a plain field
     * name takes the exact same {@link #requireColumn} path this class has always used, so the
     * common (non-join) case is byte-for-byte unchanged and an injection attempt spelled as a plain
     * (dot-free) field string still fails at {@link #requireColumn}'s whitelist check with the same
     * {@link IllegalArgumentException} it always has (rather than a
     * {@link GroupByJoinGrammar.UnsupportedGroupByPathException}, a different unchecked type that
     * would change what callers must catch).
     *
     * <p>Every value is bound as a {@link PreparedStatement} parameter via {@code whereParams} --
     * never concatenated into the returned SQL text. {@code LIKE}/{@code IN} scaffolding (wildcards,
     * escaping, placeholder count) is delegated to {@link SqlDialect}
     * ({@link SqlDialect#containsPattern}/{@link SqlDialect#startsWithPattern}/
     * {@link SqlDialect#likeEscapeClause}/{@link SqlDialect#inPlaceholders}) rather than hand-rolled
     * here, fixing the pre-R4.3 defect where {@code contains} escaped its own wildcards inline.
     */
    private String renderPredicateClause(
            ConceptShape baseShape, String baseAlias, ConceptQuery.Filter clause,
            Map<String, JoinPlan> joinsByChainKey, List<String> joinClauses, List<Object> joinParams,
            List<Object> whereParams, String tenantId) {
        String rawField = clause.field();
        ConceptShape targetShape;
        String rawColumn;
        String columnRef;
        if (rawField != null && (rawField.indexOf('.') >= 0 || rawField.contains("::"))) {
            GroupByJoinGrammar.Target target = GroupByJoinGrammar.parse(rawField);
            if (target instanceof GroupByJoinGrammar.Target.Join join) {
                JoinPlan plan = registerJoinChain(baseShape, join.referenceFields(), baseAlias,
                        joinsByChainKey, joinClauses, joinParams, tenantId);
                targetShape = plan.targetShape();
                rawColumn = requireColumn(targetShape, join.targetField());
                columnRef = plan.alias() + "." + sqlId(rawColumn);
            } else {
                // Unreachable in practice (a dot/"::" always parses to a Join), kept as a safe
                // fallback rather than an assertion so a future grammar change degrades gracefully.
                targetShape = baseShape;
                rawColumn = requireColumn(baseShape, ((GroupByJoinGrammar.Target.Direct) target).field());
                columnRef = baseAlias + "." + sqlId(rawColumn);
            }
        } else {
            targetShape = baseShape;
            rawColumn = requireColumn(baseShape, rawField);
            columnRef = baseAlias + "." + sqlId(rawColumn);
        }
        String dslType = targetShape.dslTypeByColumn().get(rawColumn.toLowerCase(Locale.ROOT));
        return switch (clause.operator()) {
            case EQ, NEQ, LT, LTE, GT, GTE -> {
                whereParams.add(coerceValue(rawColumn, clause.value(), dslType));
                yield columnRef + " " + sqlOperator(clause.operator()) + " ?";
            }
            // RUN-28 (2026-08-25 remediation plan W2.2): same WHERE-fragment shape #existsUnique's
            // text-like branch already uses for the identical uniqueValuesCollide rule -- ALWAYS
            // cast-to-text (unlike existsUnique, which only does this conditionally per dslType),
            // because EQ_CI's own contract is "compare the field's textual representation", not
            // "compare natively unless the incoming value happens to be a String". See
            // ConceptQuery.Operator#EQ_CI's javadoc.
            case EQ_CI -> {
                whereParams.add(String.valueOf(clause.value()).trim().toLowerCase(Locale.ROOT));
                yield "LOWER(" + dialect.trimmedText(dialect.cast(columnRef, SqlType.TEXT)) + ") = ?";
            }
            case CONTAINS -> {
                // The lower-cased text expression is the dialect's to spell, not this call site's:
                // MySQL's CAST has no VARCHAR target at all, and T-SQL's length-less CAST truncates
                // to 30 characters silently. See SqlDialect#caseInsensitiveTextExpression.
                whereParams.add(dialect.containsPattern(String.valueOf(clause.value()).toLowerCase(Locale.ROOT)));
                yield dialect.caseInsensitiveTextExpression(columnRef) + " LIKE ? " + dialect.likeEscapeClause();
            }
            case STARTS_WITH -> {
                whereParams.add(dialect.startsWithPattern(String.valueOf(clause.value()).toLowerCase(Locale.ROOT)));
                yield dialect.caseInsensitiveTextExpression(columnRef) + " LIKE ? " + dialect.likeEscapeClause();
            }
            case IN -> {
                List<?> values = (List<?>) clause.value();
                if (values == null || values.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Filter.in(" + rawField + ") requires at least one value -- an empty IN list is "
                                    + "refused rather than silently rendered as \"no rows match\"");
                }
                for (Object value : values) {
                    whereParams.add(coerceValue(rawColumn, value, dslType));
                }
                yield columnRef + " IN (" + dialect.inPlaceholders(values.size()) + ")";
            }
            case IS_NULL -> columnRef + " IS NULL";
            case IS_NOT_NULL -> columnRef + " IS NOT NULL";
            case OR_GROUPS -> throw new IllegalArgumentException(
                    "OR_GROUPS may only appear as the sole top-level filter, not nested inside a clause group");
        };
    }

    private int bindParams(PreparedStatement statement, List<Object> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            bindObject(statement, index++, param);
        }
        return index;
    }

    /**
     * LNCH-16: tables generated before optimistic locking existed have no {@code row_version}
     * column ({@link TableColumns#has} backward-compat check) and keep today's unconditional-upsert
     * behavior exactly as-is. Once the column is present, every write is tracked/incremented through
     * {@link #saveVersioned}, whether or not the caller actually asked for a compare-and-swap.
     */
    @Override
    public ConceptRecord save(ConceptRecord record) {
        ConceptShape shape = shape(record.conceptName());
        Map<String, Object> dbRecord = dbRecord(shape, record);
        Connection connection = openConnection();
        try {
            TableColumns columns = tableColumns(connection, shape.tableName());
            dbRecord.keySet().removeIf(column -> !columns.has(column));
            dbRecord.putIfAbsent(shape.idColumn(), coerceId(record.id()));
            if (!columns.has("row_version")) {
                executeUpsert(connection, shape, dbRecord, columns);
                return record;
            }
            return saveVersioned(connection, shape, record, dbRecord);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed saving concept " + record.conceptName() + " to JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    private void executeUpsert(
            Connection connection, ConceptShape shape, Map<String, Object> dbRecord, TableColumns columns
    ) throws SQLException {
        List<String> columnNames = new ArrayList<>(dbRecord.keySet());
        columnNames.remove(shape.idColumn());
        columnNames.add(0, shape.idColumn());
        // RAW names go in, and bindColumns() comes back raw -- executeUpsert reads dbRecord and
        // dslTypeByColumn with them, which are keyed on the unquoted name. The QUOTING happens
        // inside the dialect as it composes the statement text (STOR-6), because that is the only
        // place that both knows how to quote and can keep the bind list unquoted.
        UpsertPlan plan = upsertPlan(connection, shape.tableName(), shape.idColumn(), columnNames);
        // UpsertPlan's execution rule: run the steps in order, STOP after the first that affects a
        // row. One step on the engines whose native upsert names its conflict target; two on MySQL,
        // where the INSERT runs only if the UPDATE matched nothing, so a clash with a `unique: true`
        // column raises instead of overwriting the row that held the value (STOR-11).
        for (UpsertPlan.Step step : plan.steps()) {
            try (PreparedStatement statement = connection.prepareStatement(step.sql())) {
                int index = 1;
                for (String column : step.bindColumns()) {
                    bindObject(statement, index++,
                            coerceValue(column, dbRecord.get(column), shape.dslTypeByColumn().get(column)));
                }
                if (statement.executeUpdate() > 0) {
                    break;
                }
            }
        }
    }

    /**
     * {@code record.rowVersion() == null} is an unconditional write (create, or an explicit
     * force-update) -- still tracked through {@code row_version} so a later compare-and-swap has
     * something to compare against. A non-null rowVersion is a compare-and-swap request: the UPDATE
     * only touches the row if its stored {@code row_version} still matches what the caller last
     * read; zero rows affected means someone else won the race (or the row is gone), and the caller
     * gets a {@link ConceptStoreOptimisticLockException} carrying whatever is currently stored.
     */
    private ConceptRecord saveVersioned(
            Connection connection, ConceptShape shape, ConceptRecord record, Map<String, Object> dbRecord
    ) throws SQLException {
        if (record.rowVersion() == null) {
            long newVersion = currentRowVersion(connection, shape, record).map(version -> version + 1).orElse(0L);
            dbRecord.put("row_version", newVersion);
            executeUpsert(connection, shape, dbRecord, tableColumns(connection, shape.tableName()));
            return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), record.data(), newVersion);
        }

        long newVersion = record.rowVersion() + 1;
        List<String> columnNames = new ArrayList<>(dbRecord.keySet());
        columnNames.remove(shape.idColumn());
        columnNames.remove("row_version");
        List<String> setTerms = new ArrayList<>();
        for (String column : columnNames) {
            setTerms.add(sqlId(column) + " = ?");
        }
        setTerms.add("row_version = ?");
        String sql = "UPDATE " + sqlId(shape.tableName()) + " SET " + String.join(", ", setTerms)
                + " WHERE " + sqlId(shape.idColumn()) + " = ? AND tenant_id = ? AND row_version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String column : columnNames) {
                bindObject(statement, index++, coerceValue(column, dbRecord.get(column), shape.dslTypeByColumn().get(column)));
            }
            bindObject(statement, index++, newVersion);
            bindObject(statement, index++, coerceId(record.id()));
            bindObject(statement, index++, record.tenantId());
            bindObject(statement, index, record.rowVersion());
            int affected = statement.executeUpdate();
            if (affected == 0) {
                Optional<ConceptRecord> current = findById(record.tenantId(), record.conceptName(), record.id());
                throw new ConceptStoreOptimisticLockException(record.conceptName(), record.id(), record.tenantId(), current);
            }
        }
        return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), record.data(), newVersion);
    }

    private Optional<Long> currentRowVersion(Connection connection, ConceptShape shape, ConceptRecord record) throws SQLException {
        String sql = "SELECT row_version FROM " + sqlId(shape.tableName()) + " WHERE " + sqlId(shape.idColumn()) + " = ? AND tenant_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, coerceId(record.id()));
            bindObject(statement, 2, record.tenantId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                long value = resultSet.getLong(1);
                return resultSet.wasNull() ? Optional.empty() : Optional.of(value);
            }
        }
    }

    /**
     * R5.4: for a {@code softDelete: true} concept, "delete" flips {@code deleted_at} to now instead
     * of removing the row -- every other override in this class (findById/findAllCapped/query/
     * aggregate/existsUnique) already excludes a row once this timestamp is set, so nothing downstream
     * needs to know delete stopped being physical. The {@code deleted_at IS NULL} guard makes a
     * double-delete a harmless no-op (0 rows affected) rather than re-stamping an already-deleted row's
     * timestamp.
     */
    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        if (shape.softDelete()) {
            String sql = "UPDATE " + sqlId(shape.tableName()) + " SET deleted_at = ? WHERE "
                    + sqlId(shape.idColumn()) + " = ? AND tenant_id = ? AND deleted_at IS NULL";
            Connection connection = openConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindObject(statement, 1, java.sql.Timestamp.from(java.time.Instant.now()));
                bindObject(statement, 2, coerceId(id));
                bindObject(statement, 3, tenantId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed soft-deleting concept " + conceptName + " from JDBC store", exception);
            } finally {
                releaseConnection(connection);
            }
            return;
        }
        String sql = "DELETE FROM " + sqlId(shape.tableName()) + " WHERE " + sqlId(shape.idColumn()) + " = ? AND tenant_id = ?";
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, coerceId(id));
            bindObject(statement, 2, tenantId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed deleting concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /**
     * R5.4: the restore half of soft delete -- clears {@code deleted_at}, making the row visible to
     * every read method again. A direct {@code UPDATE}, not a {@code findById}-then-{@code save} round
     * trip, because {@code findById} deliberately EXCLUDES a deleted row (consistent "invisible by
     * default" scoping) -- going through it here would make restoring a deleted row impossible to ever
     * find. {@code deleted_at IS NOT NULL} makes restoring an already-live row a harmless no-op
     * (0 rows affected, {@code false}) rather than an error.
     *
     * @return true if a soft-deleted row was found and restored; false if the concept is not
     *         soft-delete, the row does not exist, or the row was already live
     */
    @Override
    public boolean restore(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        if (!shape.softDelete()) {
            return false;
        }
        String sql = "UPDATE " + sqlId(shape.tableName()) + " SET deleted_at = NULL WHERE "
                + sqlId(shape.idColumn()) + " = ? AND tenant_id = ? AND deleted_at IS NOT NULL";
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, coerceId(id));
            bindObject(statement, 2, tenantId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed restoring concept " + conceptName + " in JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
    }

    /** R5.4: "" for a non-soft-delete concept (identical SQL to before this feature existed) --
     *  appended, unparameterized, to every read method's {@code WHERE} clause. No bind parameter
     *  needed (a literal predicate, not a value), so string concatenation here carries no injection
     *  risk the rest of this class's parameterized queries don't already avoid elsewhere. */
    private static String deletedAtFilter(ConceptShape shape) {
        return shape.softDelete() ? " AND deleted_at IS NULL" : "";
    }

    private ConceptRecord toRecord(ConceptShape shape, String tenantId, ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        Map<String, Object> data = new LinkedHashMap<>();
        String id = "";
        Long rowVersion = null;
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String column = metaData.getColumnLabel(index);
            // Through the connection's dialect, for the same reason the bind side goes through it:
            // rs.getObject returns whatever the DRIVER decides the column is, and that differs per
            // engine for one declared model type. A `datetime` comes back zone-less on MySQL, which
            // the generated OffsetDateTime DTO cannot bind -- after a write that already succeeded
            // (STOR-10).
            Object value = SqlDialects.forConnection(resultSet.getStatement().getConnection())
                    .readValue(resultSet.getObject(index));
            // LNCH-16: row_version is tracked as its own ConceptRecord component, not a DSL field --
            // exclude it from data() so it never leaks into REST responses/generated entities.
            if ("row_version".equalsIgnoreCase(column)) {
                rowVersion = value == null ? null : ((Number) value).longValue();
                continue;
            }
            // Same rule, same reason: tenant_id is already its own ConceptRecord component
            // (tenantId, threaded through the constructor below) -- it is not a DSL field, so it must
            // not also land in data(), or it leaks into REST responses/generated entities and (found
            // live via R1 Stage 1 reviving ConceptQueryControllerExportCsvVolumeTest) CSV exports.
            if ("tenant_id".equalsIgnoreCase(column)) {
                continue;
            }
            // R5.4: deleted_at is a platform-managed column too (like row_version/tenant_id above),
            // not a DSL field -- and UNLIKE version (which falls through the generic fallback below
            // because the generated JPA entity really does declare a `version` field), the entity has
            // NO deletedAt field at all. Landing it in data() would make entityFromRecord's strict
            // Jackson convertValue throw UnrecognizedPropertyException on every read of a soft-delete
            // concept's row. Every caller that needs this state asks the WHERE clause (deletedAtFilter)
            // or the dedicated restore()/deleteById() methods, never data().
            if ("deleted_at".equalsIgnoreCase(column)) {
                continue;
            }
            if (isJsonColumnType(metaData, index) || isJsonDslField(shape, column)) {
                value = parseJsonColumnValue(column, value);
            }
            // Defense-in-depth beyond application-step0.yml's DATABASE_TO_LOWER=TRUE fix: the lookup
            // key above is already lowercased, but the fallback for a column with no DSL mapping (a
            // platform-managed column like `version`, the legacy JPA optimistic-lock field, distinct
            // from `row_version` above) must be too -- an engine/config combination that folds
            // unquoted identifiers to uppercase (H2's own default without DATABASE_TO_LOWER) would
            // otherwise hand toRuntimeField the raw uppercase label, which it returns verbatim (no
            // underscore to rewrite), landing an uppercase key in data() that the generated entity's
            // lowercase Jackson property can't deserialize.
            String field = shape.fieldByColumn().getOrDefault(column.toLowerCase(Locale.ROOT), toRuntimeField(column.toLowerCase(Locale.ROOT)));
            data.put(field, value);
            if (shape.idColumn().equalsIgnoreCase(column) && value != null) {
                id = String.valueOf(value);
            }
        }
        return new ConceptRecord(shape.conceptName(), id, tenantId, data, rowVersion);
    }

    private static boolean isJsonColumnType(ResultSetMetaData metaData, int index) throws SQLException {
        // Asks the dialect rather than spelling the two type names here for the third time. The set
        // is per-engine (MySQL knows only JSON; Postgres reports jsonb) and both spellings had to be
        // repeated correctly at every site before this.
        return SqlDialects.active().isJsonColumnType(metaData.getColumnTypeName(index));
    }

    /**
     * HARDEN-GC: {@code isJsonColumnType} trusts the JDBC driver's reported column type name, which
     * H2 does not reliably report as "JSON"/"JSONB" for a JSON column (confirmed live: a {@code
     * file}-typed field's column came back with a type name that failed that check, so the already
     * JSON-encoded write-side string was handed straight through instead of being parsed --
     * `attachment` field values round-tripped as a raw JSON string instead of a nested
     * object/array, silently defeating any code reading the field's structure, e.g. the
     * delete/replace-cascade file-field extraction). The DSL's own declared type (object/array/file
     * all map to a JSON/JSONB column per SqlTypeSupport) is authoritative and engine-independent,
     * so prefer it over trusting the driver.
     */
    private static boolean isJsonDslField(ConceptShape shape, String column) {
        String dslType = shape.dslTypeByColumn().get(column.toLowerCase(Locale.ROOT));
        return "object".equalsIgnoreCase(dslType) || "array".equalsIgnoreCase(dslType) || "file".equalsIgnoreCase(dslType);
    }

    /**
     * The write side (coerceValue) stores object/array DSL fields as JSON text, since neither H2
     * nor Postgres accept a raw Java Map/List for a JSON-typed column. Reading it back, the JDBC
     * driver hands the column back as a String (or, on some drivers, raw bytes) -- parse it back
     * into the Map/List the rest of the runtime (entity mapping, JSON serialization to the
     * generated REST response, the business UI's object/array renderer) expects, so the round trip
     * is transparent: nothing downstream needs to know this field is JSON-backed.
     */
    private static Object parseJsonColumnValue(String column, Object value) {
        if (value == null) {
            return null;
        }
        String json;
        if (value instanceof byte[] bytes) {
            json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } else if (value instanceof String text) {
            json = text;
        } else {
            // Already a structured value (some drivers may already deserialize JSON columns) --
            // leave it as-is rather than guessing.
            return value;
        }
        if (json.isBlank()) {
            return null;
        }
        try {
            return JSON_COLUMN_MAPPER.readValue(json, Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse JSON column \"" + column + "\"", exception);
        }
    }

    private Map<String, Object> dbRecord(ConceptShape shape, ConceptRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.data().entrySet()) {
            String column = shape.columnByField().getOrDefault(entry.getKey().toLowerCase(Locale.ROOT), toDbColumn(entry.getKey()));
            out.put(column, entry.getValue());
        }
        // id/tenant_id are applied LAST, unconditionally, so they always win over a same-named
        // entry record.data() happens to carry — not merely never collide with one by convention.
        // (REG-169: mapFromEntity()-style blanket entity serialization can put a stale/unhydrated
        // "tenantId" into data() on an UPDATE path that never re-stamps the loaded entity before
        // save; with tenant_id applied first, that entry silently clobbered the correct value.)
        out.put(shape.idColumn(), coerceId(record.id()));
        out.put("tenant_id", record.tenantId());
        return out;
    }

    private ConceptShape shape(String conceptName) {
        ConceptShape shape = shapesByConcept.get(normalize(conceptName));
        if (shape == null) {
            throw new IllegalArgumentException("Unknown concept for JDBC ConceptStore: " + conceptName);
        }
        return shape;
    }

    private TableColumns tableColumns(Connection connection, String table) {
        return tableColumnsCache.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> loadColumns(connection, table));
    }

    private static Map<String, ConceptShape> shapes(CompiledModel model) {
        Map<String, ConceptShape> out = new LinkedHashMap<>();
        if (model == null) {
            return Map.of();
        }
        for (CompiledConcept concept : model.getConcepts()) {
            String table = concept.getTableName();
            if (table == null || table.isBlank()) {
                table = toDbColumn(concept.getName()) + "s";
            }
            String idColumn = "id";
            Map<String, String> columnByField = new LinkedHashMap<>();
            Map<String, String> fieldByColumn = new LinkedHashMap<>();
            Map<String, String> dslTypeByColumn = new LinkedHashMap<>();
            Map<String, String> referenceTargetByField = new LinkedHashMap<>();
            for (CompiledField field : concept.getFields()) {
                String column = toDbColumn(field.getName());
                columnByField.put(field.getName().toLowerCase(Locale.ROOT), column);
                fieldByColumn.put(column.toLowerCase(Locale.ROOT), field.getName());
                dslTypeByColumn.put(column.toLowerCase(Locale.ROOT), field.getDslType());
                if (field.getReferenceTarget() != null && !field.getReferenceTarget().isBlank()) {
                    referenceTargetByField.put(field.getName().toLowerCase(Locale.ROOT), field.getReferenceTarget());
                }
                if (field.isId()) {
                    idColumn = column;
                }
            }
            out.put(normalize(concept.getName()), new ConceptShape(
                    concept.getName(), table, idColumn, columnByField, fieldByColumn, dslTypeByColumn,
                    referenceTargetByField, concept.isSoftDelete()));
        }
        return Map.copyOf(out);
    }

    private static TableColumns loadColumns(Connection connection, String table) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            TableColumns columns = new TableColumns();
            try (ResultSet resultSet = metaData.getColumns(null, null, table, null)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME"));
                }
            }
            if (columns.empty()) {
                try (ResultSet resultSet = metaData.getColumns(null, null, table.toUpperCase(Locale.ROOT), null)) {
                    while (resultSet.next()) {
                        columns.add(resultSet.getString("COLUMN_NAME"));
                    }
                }
            }
            return columns;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed loading columns for " + table, exception);
        }
    }

    /**
     * The last step before {@code setObject}: let the connection's dialect shape the value, then bind
     * it at {@code index}.
     *
     * <p>Every bind in this class goes through here. Before STOR-10 they went straight to
     * {@code setObject}, which is correct on the two engines this store was written
     * against and silently wrong on the other two -- MySQL Java-SERIALIZED the
     * {@code java.util.UUID} the coercion helpers produce and reported it as an
     * {@code Incorrect string value} on the {@code id} column, the offending bytes being
     * the Java serialization stream header ({@code 0xACED0005}) rather than text at all.
     *
     * <p>Resolving the dialect from the statement's own
     * connection (rather than {@code SqlDialects.active()}) keeps cross-engine promotion correct,
     * which is the same reason {@code buildUpsertSql} is connection-driven.
     *
     * <p>A dialect that needs the 3-arg {@code setObject(index, value, sqlType)} form (Postgres, for
     * a json/jsonb column -- see {@code PostgresDialect#bindableValue}) returns a
     * {@link com.npdev.kernel.storage.sql.TypedBindValue} instead of the plain shaped value; every
     * other dialect keeps returning the value directly, unwrapped by the 2-arg branch below exactly
     * as before this method also took over the actual {@code setObject} call.
     */
    private static void bindObject(PreparedStatement statement, int index, Object value) throws SQLException {
        Object shaped = SqlDialects.forConnection(statement.getConnection()).bindableValue(value);
        if (shaped instanceof com.npdev.kernel.storage.sql.TypedBindValue typed) {
            statement.setObject(index, typed.value(), typed.sqlType());
        } else {
            statement.setObject(index, shaped);
        }
    }

    /**
     * PRE-EXTRACTION this method WAS the dialect layer, spelling H2's MERGE and Postgres's ON
     * CONFLICT by hand behind a product-name probe. Kept connection-driven (rather than reading the
     * app's configured engine) because the probe is what it always was; only the statements moved.
     *
     * <p>The probe stayed a TWO-WAY choice until STOR-10 -- H2 or else Postgres -- so on MySQL and
     * SQL Server this returned a Postgres upsert that neither engine can parse. Sibling of the
     * identical bug in PostgresPersistenceCapabilityAdapter; both now ask
     * {@link SqlDialects#forConnection}, which refuses an engine it does not know rather than
     * assuming one.
     */
    private static UpsertPlan upsertPlan(Connection connection, String table, String idColumn, List<String> columns) throws SQLException {
        SqlDialect connectionDialect = SqlDialects.forConnection(connection);
        return connectionDialect.upsert().planFor(table, List.of(idColumn), columns);
    }

    private static final ObjectMapper JSON_COLUMN_MAPPER = new ObjectMapper();

    private static Object coerceValue(String column, Object value, String dslType) {
        if (value == null) {
            return null;
        }
        if (isUuidColumn(column)) {
            return coerceId(value);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            // Object/array/file DSL fields map to a JSON/JSONB column (SqlTypeSupport). Handing the
            // JDBC driver a raw Map/List makes it default to JAVA_OBJECT, which H2 (and Postgres)
            // both reject for a JSON-typed column ("Data conversion error converting JAVA_OBJECT
            // to JSON"). Binding the JSON text as a java.lang.String isn't safe either: H2's JSON
            // column treats a bound String as a JSON *string value* and quotes/escapes it rather
            // than storing it as the object it represents (confirmed live: a file field's handle
            // round-tripped as a JSON-encoded string instead of a nested object, silently defeating
            // GeneratedCrudRuntimeSupport's file-field extraction on delete/replace-cascade).
            // Binding raw JSON bytes instead is accepted as JSON content by both H2 and Postgres.
            try {
                return JSON_COLUMN_MAPPER.writeValueAsBytes(value);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to serialize column \"" + column + "\" to JSON", exception);
            }
        }
        if (value instanceof String text && !text.isBlank()) {
            // A "date"/"datetime" DSL field's JSON value is always a plain ISO-8601 string (the
            // REST layer never sends a java.sql.Date/Timestamp). H2's JDBC driver silently casts a
            // bound VARCHAR into a DATE/TIMESTAMP column; real Postgres does not
            // ("column is of type date but expression is of type character varying") and rejects
            // it outright, so every date/datetime-bearing concept (Lote, Recebimento, Expedicao,
            // Movimento, DocumentoFiscal, InventarioArquivo, ...) failed to save under Postgres
            // specifically until this conversion was added.
            if ("date".equals(dslType)) {
                return java.sql.Date.valueOf(java.time.LocalDate.parse(text));
            }
            if ("datetime".equals(dslType)) {
                return java.sql.Timestamp.from(parseDateTime(text));
            }
            // LNCH-5: a filter value arriving as a String (e.g. a REST query parameter) against a
            // numeric column must bind as a number -- H2 silently casts a VARCHAR, but real Postgres
            // rejects "int = varchar". Leave non-numeric text untouched.
            if (isNumericDslType(dslType)) {
                try {
                    return new java.math.BigDecimal(text.trim());
                } catch (NumberFormatException ignored) {
                    return value;
                }
            }
        }
        return value;
    }

    private static boolean isNumericDslType(String dslType) {
        if (dslType == null) {
            return false;
        }
        return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
            case "int", "integer", "long", "bigint", "decimal", "number", "numeric",
                    "float", "double", "money", "currency" -> true;
            default -> false;
        };
    }

    private static java.time.Instant parseDateTime(String text) {
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
    }

    private static Object coerceId(Object value) {
        if (value instanceof UUID) {
            return value;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static boolean isUuidColumn(String column) {
        String normalized = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return "id".equals(normalized) || normalized.endsWith("_id");
    }

    private static String toDbColumn(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String toRuntimeField(String column) {
        if (column == null || column.isBlank() || !column.contains("_")) {
            return column;
        }
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ConceptShape(
            String conceptName,
            String tableName,
            String idColumn,
            Map<String, String> columnByField,
            Map<String, String> fieldByColumn,
            Map<String, String> dslTypeByColumn,
            /** S4: field name (lowercased) -> declared {@code reference.target} concept name, for
             *  fields that ARE a reference. Absent for any non-reference field. */
            Map<String, String> referenceTargetByField,
            /** R5.4: whether this concept declares {@code softDelete: true} -- gates every read method
             *  below to exclude a row whose {@code deleted_at} column is set, and {@link #deleteById}
             *  to flip that timestamp instead of physically removing the row. */
            boolean softDelete
    ) {
    }

    private static final class TableColumns {
        private final List<String> columns = new ArrayList<>();

        void add(String column) {
            if (column != null && !column.isBlank()) {
                columns.add(column.toLowerCase(Locale.ROOT));
            }
        }

        boolean has(String column) {
            return column != null && columns.contains(column.toLowerCase(Locale.ROOT));
        }

        boolean empty() {
            return columns.isEmpty();
        }
    }
}
