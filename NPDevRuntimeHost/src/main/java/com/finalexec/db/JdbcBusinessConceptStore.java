package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.query.GroupByJoinGrammar;
import com.npdev.kernel.concepts.ConceptAggregateEngine;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
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
        String sql = "SELECT * FROM " + shape.tableName() + " WHERE " + shape.idColumn() + " = ? AND tenant_id = ?";
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, bindable(statement, coerceId(id)));
            statement.setObject(2, bindable(statement, tenantId));
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
                "*", shape.tableName(), shape.idColumn() + " = ? AND tenant_id = ?");
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, bindable(statement, coerceId(id)));
            statement.setObject(2, bindable(statement, tenantId));
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
        String sql = "SELECT * FROM " + shape.tableName() + " WHERE tenant_id = ? ORDER BY " + shape.idColumn();
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, bindable(statement, tenantId));
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
     * LNCH-5: pushes the filter/sort/page window down to SQL. Column names are resolved through the
     * compiled model's field->column whitelist (never taken from raw input), and every filter value
     * is a bound parameter, so this is not a string-concatenation injection surface. {@code total} is
     * a matching {@code COUNT(*)} rather than a materialize-everything count, and {@code LIMIT}/{@code
     * OFFSET} keep the JVM from ever holding more than one page. A stable {@code ORDER BY} (the id
     * column when the caller declares no sort) makes OFFSET paging deterministic.
     */
    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        ConceptShape shape = shape(conceptName);
        ConceptQuery effective = query == null ? ConceptQuery.firstPage() : query;

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add("tenant_id = ?");
        params.add(tenantId);
        for (ConceptQuery.Filter filter : effective.filters()) {
            String column = requireColumn(shape, filter.field());
            if (filter.operator() == ConceptQuery.Operator.CONTAINS) {
                // CAST to VARCHAR first: Postgres's LOWER() rejects non-text input outright (unlike
                // H2, which silently coerces), so a "contains" filter against a numeric/UUID column
                // would otherwise work under H2 in dev and fail under Postgres in production.
                whereClauses.add("LOWER(CAST(" + column + " AS VARCHAR)) LIKE ? ESCAPE '\\'");
                params.add("%" + likeEscape(String.valueOf(filter.value()).toLowerCase(Locale.ROOT)) + "%");
                continue;
            }
            String dslType = shape.dslTypeByColumn().get(column.toLowerCase(Locale.ROOT));
            whereClauses.add(column + " " + sqlOperator(filter.operator()) + " ?");
            params.add(coerceValue(column, filter.value(), dslType));
        }
        String whereSql = String.join(" AND ", whereClauses);
        String orderSql = orderByClause(shape, effective.sorts());

        Connection connection = openConnection();
        try {
            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + shape.tableName() + " WHERE " + whereSql)) {
                bindParams(statement, params, 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    total = resultSet.getLong(1);
                }
            }

            List<ConceptRecord> items = new ArrayList<>();
            String pageSql = dialect.paginated("SELECT * FROM " + shape.tableName() + " WHERE " + whereSql
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
        for (ConceptQuery.Filter filter : query.filters()) {
            String rawColumn = requireColumn(shape, filter.field());
            String column = baseAlias + "." + rawColumn;
            if (filter.operator() == ConceptQuery.Operator.CONTAINS) {
                whereClauses.add("LOWER(CAST(" + column + " AS VARCHAR)) LIKE ? ESCAPE '\\'");
                whereParams.add("%" + likeEscape(String.valueOf(filter.value()).toLowerCase(Locale.ROOT)) + "%");
                continue;
            }
            String dslType = shape.dslTypeByColumn().get(rawColumn.toLowerCase(Locale.ROOT));
            whereClauses.add(column + " " + sqlOperator(filter.operator()) + " ?");
            whereParams.add(coerceValue(rawColumn, filter.value(), dslType));
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
                .append(" FROM ").append(shape.tableName()).append(" AS ").append(baseAlias);
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

    private String orderByClause(ConceptShape shape, List<ConceptQuery.Sort> sorts) {
        if (sorts.isEmpty()) {
            // OFFSET paging is only deterministic under a stable order; default to the primary key.
            return " ORDER BY " + shape.idColumn();
        }
        List<String> terms = new ArrayList<>();
        for (ConceptQuery.Sort sort : sorts) {
            String column = requireColumn(shape, sort.field());
            terms.add(column + (sort.descending() ? " DESC" : " ASC"));
        }
        return " ORDER BY " + String.join(", ", terms);
    }

    private static String sqlOperator(ConceptQuery.Operator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NEQ -> "<>";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case CONTAINS -> throw new IllegalStateException("CONTAINS is compiled to LIKE, not a binary operator");
        };
    }

    /** Escapes LIKE wildcard characters in a literal search term bound as a parameter. */
    private static String likeEscape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int bindParams(PreparedStatement statement, List<Object> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            statement.setObject(index++, bindable(statement, param));
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
        String sql = upsertSql(connection, shape.tableName(), shape.idColumn(), columnNames);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String column : columnNames) {
                statement.setObject(index++, bindable(statement, coerceValue(column, dbRecord.get(column), shape.dslTypeByColumn().get(column))));
            }
            statement.executeUpdate();
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
            setTerms.add(column + " = ?");
        }
        setTerms.add("row_version = ?");
        String sql = "UPDATE " + shape.tableName() + " SET " + String.join(", ", setTerms)
                + " WHERE " + shape.idColumn() + " = ? AND tenant_id = ? AND row_version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String column : columnNames) {
                statement.setObject(index++, bindable(statement, coerceValue(column, dbRecord.get(column), shape.dslTypeByColumn().get(column))));
            }
            statement.setObject(index++, bindable(statement, newVersion));
            statement.setObject(index++, bindable(statement, coerceId(record.id())));
            statement.setObject(index++, bindable(statement, record.tenantId()));
            statement.setObject(index, bindable(statement, record.rowVersion()));
            int affected = statement.executeUpdate();
            if (affected == 0) {
                Optional<ConceptRecord> current = findById(record.tenantId(), record.conceptName(), record.id());
                throw new ConceptStoreOptimisticLockException(record.conceptName(), record.id(), record.tenantId(), current);
            }
        }
        return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), record.data(), newVersion);
    }

    private Optional<Long> currentRowVersion(Connection connection, ConceptShape shape, ConceptRecord record) throws SQLException {
        String sql = "SELECT row_version FROM " + shape.tableName() + " WHERE " + shape.idColumn() + " = ? AND tenant_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, bindable(statement, coerceId(record.id())));
            statement.setObject(2, bindable(statement, record.tenantId()));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                long value = resultSet.getLong(1);
                return resultSet.wasNull() ? Optional.empty() : Optional.of(value);
            }
        }
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        String sql = "DELETE FROM " + shape.tableName() + " WHERE " + shape.idColumn() + " = ? AND tenant_id = ?";
        Connection connection = openConnection();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, bindable(statement, coerceId(id)));
            statement.setObject(2, bindable(statement, tenantId));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed deleting concept " + conceptName + " from JDBC store", exception);
        } finally {
            releaseConnection(connection);
        }
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
        out.put(shape.idColumn(), coerceId(record.id()));
        // tenant_id has no safe DB-level default (unlike "version" DEFAULT 0) — it must come from
        // the ConceptRecord's own dedicated tenantId component, not record.data(). The
        // kernel-gateway write path (DefaultConceptGateway.save -> store.save) builds its payload
        // from DSL-declared fields only and never puts a "tenantId" entry into data(), so relying on
        // record.data() alone (as this loop does for every other column) would silently write NULL.
        out.put("tenant_id", record.tenantId());
        for (Map.Entry<String, Object> entry : record.data().entrySet()) {
            String column = shape.columnByField().getOrDefault(entry.getKey().toLowerCase(Locale.ROOT), toDbColumn(entry.getKey()));
            out.put(column, entry.getValue());
        }
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
                    concept.getName(), table, idColumn, columnByField, fieldByColumn, dslTypeByColumn, referenceTargetByField));
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
    /**
     * The last step before {@code setObject}: let the connection's dialect shape the value.
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
     */
    private static Object bindable(PreparedStatement statement, Object value) throws SQLException {
        return SqlDialects.forConnection(statement.getConnection()).bindableValue(value);
    }

    private static String upsertSql(Connection connection, String table, String idColumn, List<String> columns) throws SQLException {
        SqlDialect connectionDialect = SqlDialects.forConnection(connection);
        return connectionDialect.upsert().statementFor(table, List.of(idColumn), columns);
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
            Map<String, String> referenceTargetByField
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
