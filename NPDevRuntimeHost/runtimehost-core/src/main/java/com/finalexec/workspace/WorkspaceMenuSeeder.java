package com.finalexec.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPDev's first genuinely automatic (no human/curl trigger) data-seeding mechanism: on every
 * boot, if the {@code workspace::Menu} concept's table is empty for the configured tenant, inserts
 * the default rows BusinessUiEmitter derived at generation time from the app's own persisted
 * concepts/declared Panels, plus any hand-authored companion pages declared in
 * definition/pages.json -- so both the generic business UI and any app's own shell.js get a
 * working nav with zero manual setup. Self-disabling exactly like BootstrapAdminController: once
 * any row exists, this never touches the table again, so an app author's edits via generic CRUD
 * are permanent.
 *
 * <p>The physical table name is resolved from the compiled model at construction time
 * ({@link #resolveMenuTable}) rather than hardcoded, because it is pack-versioned
 * (e.g. {@code workspace_v1_menus} today) and the generator, not this class, owns that naming
 * scheme -- see REG-160.</p>
 *
 * <p>Reads from TWO separate classpath resources, deliberately never merged at build time:
 * {@code npdev-seed/workspace-menu-seed.json} (required -- written by BusinessUiEmitter into the
 * hash-verified {@code npdev-generated/} tree; {@code @ConditionalOnResource} on this one makes
 * the whole bean a no-op for any app that doesn't compose the workspace pack) and
 * {@code npdev-seed/workspace-menu-pages-seed.json} (optional -- written by Build-NpdevApp.ps1
 * directly under the app's own, non-generated resources from definition/pages.json). They can't
 * be merged into one file before the build the way BootstrapAdminController-style seeding might
 * suggest: StrictExecutionValidator hashes the entire npdev-generated/ tree at generation time and
 * refuses to boot on any later edit to a file inside it (confirmed live) -- so the page-derived
 * rows have to live in a second, separate, non-generated file instead.</p>
 *
 * <p>Rows may additionally carry an authoring-only {@code key} / {@code parentKey} pair -- written
 * by Build-NpdevApp.ps1 when it flattens an app's optional {@code definition/menu.json} hierarchy --
 * so that a multi-level menu can be declared as a tree at authoring time while still resolving to
 * real {@code parent_menu_id} references at seed time (see {@link #run}).</p>
 */
@Component
@ConditionalOnResource(resources = "classpath:npdev-seed/workspace-menu-seed.json")
public final class WorkspaceMenuSeeder implements ApplicationRunner {

    private static final String FINGERPRINT_TARGET_PREFIX = "npdev:seed-fingerprint:";
    private static final String MODE_INSERT_IF_EMPTY = "insert-if-empty";
    private static final String MODE_UPSERT_IF_FINGERPRINT_CHANGED = "upsert-if-fingerprint-changed";
    private static final String MODE_RECONCILE = "reconcile";
    private static final String MENU_CONCEPT_NAME = "workspace::Menu";
    private static final String ORIGIN_GENERATED = "generated";
    private static final String ORIGIN_USER = "user";

    private final DataSource dataSource;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String tenantId;
    private final String seedMode;
    // REG-208 (B28 lift): resolved fresh from modelHolder.get() at run() time (below), not cached
    // here at construction -- the physical table name is pack-version-derived, not concept-content-
    // derived, so a hot reload rarely changes it, but resolving it fresh costs nothing and is
    // correct if it ever does. NOTE: re-running the seed logic itself on a reload would not surface
    // any NEW menu content -- the seed JSON this reads is a BUILD-TIME artifact (BusinessUiEmitter),
    // not derived from the live in-memory model -- so this class deliberately registers no
    // ModelReloadListener; only its ApplicationRunner#run() (boot-time) entry point still applies.
    private final ModelHolder modelHolder;

    public WorkspaceMenuSeeder(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            ModelHolder modelHolder,
            @Value("${npdev.workspace.menu-seed.tenant-id:dev}") String tenantId,
            @Value("${npdev.workspace.menu-seed.mode:reconcile}") String seedMode
    ) {
        this.dataSource = dataSource;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? "dev" : tenantId.trim();
        String trimmedMode = seedMode == null ? null : seedMode.trim();
        if (MODE_UPSERT_IF_FINGERPRINT_CHANGED.equals(trimmedMode)) {
            this.seedMode = MODE_UPSERT_IF_FINGERPRINT_CHANGED;
        } else if (MODE_INSERT_IF_EMPTY.equals(trimmedMode)) {
            this.seedMode = MODE_INSERT_IF_EMPTY;
        } else {
            // W1.2: default. Neither older mode actually reconciles -- insert-if-empty never
            // revisits a non-empty table (a model change never reaches the nav), and
            // upsert-if-fingerprint-changed truncates the whole table on ANY change (destroying
            // every manual edit). Both stay available, opt-in, for an app that already depends on
            // their exact semantics; see WORKSPACE-MENU-AND-GUIDEPAGES.md.
            this.seedMode = MODE_RECONCILE;
        }
        this.modelHolder = modelHolder;
    }

    // REG-160: resolves the physical, pack-versioned table name the generator actually created
    // for the workspace pack's Menu concept (e.g. "workspace_v1_menus" today), rather than
    // hardcoding that literal a second time here. The generator computes it from the composing
    // pack's own declared major version (see SqlIdentifierSupport.physicalTableNameSource /
    // ModelCompiler in NPDevContract/dsl) -- a future pack version bump changes the table name on
    // the generator side, and this resolution follows it automatically instead of silently
    // drifting the way the previous hardcoded "workspace_menus" literal did. This bean is only
    // ever active (see the class's @ConditionalOnResource) for an app that composed the workspace
    // pack's Menu concept, so an absent concept here indicates a genuine platform inconsistency,
    // not a normal runtime condition -- hence the hard failure rather than a silent fallback.
    private static String resolveMenuTable(CompiledModel compiledModel) {
        return compiledModel.findConcept(MENU_CONCEPT_NAME)
                .map(CompiledConcept::getTableName)
                .filter(name -> name != null && !name.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "WorkspaceMenuSeeder: compiled model has no usable table name for concept '"
                                + MENU_CONCEPT_NAME + "' -- this seeder is only active when the workspace "
                                + "pack's Menu concept is composed, so this should be unreachable"));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String menuTable = resolveMenuTable(modelHolder.get());
        try (Connection connection = dataSource.getConnection()) {
            List<JsonNode> rows = new ArrayList<>();
            readSeedResource("classpath:npdev-seed/workspace-menu-seed.json", true).forEach(rows::add);
            readSeedResource("classpath:npdev-seed/workspace-menu-pages-seed.json", false).forEach(rows::add);

            if (MODE_RECONCILE.equals(seedMode)) {
                reconcile(connection, menuTable, rows);
                return;
            }

            String fingerprint = computeFingerprint(rows);

            if (MODE_UPSERT_IF_FINGERPRINT_CHANGED.equals(seedMode)) {
                String existingFingerprint = findExistingFingerprint(connection, menuTable);
                if (fingerprint.equals(existingFingerprint)) {
                    return; // already seeded from this exact declared menu -- nothing to do.
                }
                // Deliberately destructive: this mode is opt-in specifically to pick up authoring
                // changes on redeploy, which means any manual edit/addition an app author made
                // through generic CRUD since the last seed is wiped along with it. The default mode
                // below (insert-if-empty) is what protects those edits; switching to this one is a
                // documented, informed tradeoff (see WORKSPACE-MENU-AND-GUIDEPAGES.md).
                deleteExistingMenus(connection, menuTable);
            } else if (countExistingMenus(connection, menuTable) > 0) {
                return;
            }

            // Rows may carry an authoring-only "key" (and a "parentKey" pointing at another row's
            // key) so the hierarchical menu.json tree can be flattened before generation while still
            // resolving to real UUIDs at seed time. Pre-assign one UUID per row -- keyed rows are
            // also indexed by key -- then insert with parent_menu_id resolved from parentKey via that
            // map. Keyless rows (the pre-hierarchy pages.json shape) and rows whose parentKey doesn't
            // resolve simply seed a NULL parent, i.e. a root node, exactly as before.
            Map<String, UUID> idByKey = new HashMap<>();
            List<UUID> idByRow = new ArrayList<>(rows.size());
            for (JsonNode row : rows) {
                UUID id = UUID.randomUUID();
                idByRow.add(id);
                JsonNode key = row.get("key");
                if (key != null && !key.isNull() && !key.asText("").isBlank()) {
                    idByKey.put(key.asText(), id);
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                JsonNode parentKey = row.get("parentKey");
                UUID parentId = (parentKey == null || parentKey.isNull()) ? null : idByKey.get(parentKey.asText());
                insertMenuRow(connection, menuTable, row, idByRow.get(i), parentId);
            }
            if (MODE_UPSERT_IF_FINGERPRINT_CHANGED.equals(seedMode)) {
                insertFingerprintRow(connection, menuTable, fingerprint);
            }
            System.out.println("WorkspaceMenuSeeder: seeded " + rows.size()
                    + " row(s) into " + menuTable + " for tenant '" + tenantId + "' (mode: " + seedMode + ").");
        }
    }

    private int countExistingMenus(Connection connection, String menuTable) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + menuTable + " WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // Deterministic over the merged, pre-id-assignment seed rows -- any change to definition/
    // menu.json or pages.json (or the generator's own derived seed) changes this hash, which is
    // exactly the signal upsert-if-fingerprint-changed mode reseeds on.
    private String computeFingerprint(List<JsonNode> rows) throws Exception {
        var arrayNode = objectMapper.createArrayNode();
        rows.forEach(arrayNode::add);
        return sha256Hex(objectMapper.writeValueAsString(arrayNode));
    }

    private static String sha256Hex(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    // Per-row content fingerprint -- deliberately excludes parent placement (see reconcile()'s
    // class-level note) so a pure re-grouping move alone is not treated as drift. Used identically
    // for a fresh seed row's declared content and for a DB row's live column values, so the two are
    // directly comparable.
    private String rowContentFingerprint(String label, String target, String kind, int ordinal,
                                          boolean visible, String requiredRole) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("label", label == null ? "" : label);
        node.put("target", target == null ? "" : target);
        node.put("kind", kind == null ? "" : kind);
        node.put("ordinal", ordinal);
        node.put("visible", visible);
        node.put("requiredRole", requiredRole == null ? "" : requiredRole);
        return sha256Hex(objectMapper.writeValueAsString(node));
    }

    private String rowContentFingerprint(JsonNode row) throws Exception {
        JsonNode requiredRoleNode = row.get("requiredRole");
        String requiredRole = requiredRoleNode == null || requiredRoleNode.isNull() ? null : requiredRoleNode.asText();
        return rowContentFingerprint(
                row.path("label").asText(""),
                row.path("target").asText(""),
                row.path("kind").asText("BUSINESS"),
                row.path("ordinal").asInt(0),
                row.path("visible").asBoolean(true),
                requiredRole);
    }

    private static String seedKeyOf(JsonNode row) {
        JsonNode key = row.get("key");
        if (key != null && !key.isNull() && !key.asText("").isBlank()) {
            return key.asText();
        }
        // Defensive fallback for a seed row written by a source that predates seedKey (W1.2) --
        // degraded (no ancestry, so a same-named GROUP under a different parent could collide) but
        // still lets reconcile track the row instead of silently never reconciling it.
        String kind = row.path("kind").asText("BUSINESS");
        String target = row.path("target").asText("");
        return target.isBlank() ? kind + ":" + row.path("label").asText("") : kind + ":" + target;
    }

    /**
     * W1.2 default mode: for each declared seed row, insert it if no row with its seedKey exists
     * yet; if one exists and is still origin=generated, update it in place when the seed content
     * for that key changed since the last reconcile, EXCEPT that a row a user has since edited
     * through generic CRUD (its live content has drifted from what the seeder last wrote) is not
     * silently overwritten -- per decision D7 ("model wins; the user edit is preserved as an
     * explicit override row carrying provenance"), the prior content is cloned into a new,
     * invisible, seedKey-less row (origin=user, overrideOf=<this row's seedKey>) before the
     * canonical row is updated. A generated row whose seedKey no longer appears in the current
     * seed set is removed. A row with no seedKey, or seedOrigin != generated (a user-created row,
     * or a previously preserved override clone), is never touched.
     */
    private void reconcile(Connection connection, String menuTable, List<JsonNode> rows) throws Exception {
        Map<String, ExistingMenuRow> existingByKey = loadExistingReconcilableRows(connection, menuTable);

        Map<String, JsonNode> seedRowByKey = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            seedRowByKey.put(seedKeyOf(row), row);
        }

        Map<String, UUID> finalIdByKey = new HashMap<>();
        for (String key : seedRowByKey.keySet()) {
            ExistingMenuRow existing = existingByKey.get(key);
            finalIdByKey.put(key, existing != null ? existing.id : UUID.randomUUID());
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int preserved = 0;
        for (Map.Entry<String, JsonNode> entry : seedRowByKey.entrySet()) {
            String key = entry.getKey();
            JsonNode row = entry.getValue();
            UUID id = finalIdByKey.get(key);
            JsonNode parentKeyNode = row.get("parentKey");
            UUID parentId = (parentKeyNode == null || parentKeyNode.isNull() || parentKeyNode.asText("").isBlank())
                    ? null
                    : finalIdByKey.get(parentKeyNode.asText());
            String freshFingerprint = rowContentFingerprint(row);
            ExistingMenuRow existing = existingByKey.get(key);

            if (existing == null) {
                insertReconciledRow(connection, menuTable, row, id, parentId, key, freshFingerprint, null, ORIGIN_GENERATED);
                inserted++;
                continue;
            }
            if (!ORIGIN_GENERATED.equals(existing.seedOrigin)) {
                unchanged++;
                continue;
            }
            boolean modelChanged = !freshFingerprint.equals(existing.seedFingerprint);
            if (!modelChanged) {
                unchanged++;
                continue;
            }
            boolean userEdited = !existing.currentFingerprint.equals(existing.seedFingerprint);
            if (userEdited) {
                cloneAsPreservedOverride(connection, menuTable, existing, key);
                preserved++;
            }
            updateReconciledRow(connection, menuTable, id, row, parentId, freshFingerprint);
            updated++;
        }

        int removed = 0;
        for (Map.Entry<String, ExistingMenuRow> entry : existingByKey.entrySet()) {
            if (!ORIGIN_GENERATED.equals(entry.getValue().seedOrigin) || seedRowByKey.containsKey(entry.getKey())) {
                continue;
            }
            deleteMenuRow(connection, menuTable, entry.getValue().id);
            removed++;
        }

        System.out.println("WorkspaceMenuSeeder: reconciled " + menuTable + " for tenant '" + tenantId + "' -- "
                + inserted + " inserted, " + updated + " updated, " + removed + " removed, "
                + preserved + " preserved-as-override, " + unchanged + " unchanged.");
    }

    private record ExistingMenuRow(UUID id, String seedOrigin, String seedFingerprint, String currentFingerprint,
                                    String label, String target, String kind, int ordinal, boolean visible,
                                    String requiredRole) {
    }

    private Map<String, ExistingMenuRow> loadExistingReconcilableRows(Connection connection, String menuTable) throws Exception {
        Map<String, ExistingMenuRow> byKey = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, seed_key, seed_origin, seed_fingerprint, label, target, kind, ordinal, visible, required_role "
                        + "FROM " + menuTable + " WHERE tenant_id = ? AND seed_key IS NOT NULL")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String seedKey = rs.getString("seed_key");
                    String label = rs.getString("label");
                    String target = rs.getString("target");
                    String kind = rs.getString("kind");
                    int ordinal = rs.getInt("ordinal");
                    boolean visible = rs.getBoolean("visible");
                    String requiredRole = rs.getString("required_role");
                    String currentFingerprint = rowContentFingerprint(label, target, kind, ordinal, visible, requiredRole);
                    byKey.put(seedKey, new ExistingMenuRow(
                            (UUID) rs.getObject("id"), rs.getString("seed_origin"), rs.getString("seed_fingerprint"),
                            currentFingerprint, label, target, kind, ordinal, visible, requiredRole));
                }
            }
        }
        return byKey;
    }

    private void insertReconciledRow(Connection connection, String menuTable, JsonNode row, UUID id, UUID parentId,
                                      String seedKey, String seedFingerprint, String overrideOf, String origin) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + menuTable + " (id, label, target, kind, parent_menu_id, required_role, ordinal, "
                        + "visible, tenant_id, seed_key, seed_origin, seed_fingerprint, override_of) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, row.path("label").asText(""));
            ps.setString(3, row.path("target").asText(""));
            ps.setString(4, row.path("kind").asText("BUSINESS"));
            ps.setString(5, parentId == null ? null : parentId.toString());
            JsonNode requiredRole = row.get("requiredRole");
            ps.setString(6, requiredRole == null || requiredRole.isNull() ? null : requiredRole.asText());
            ps.setInt(7, row.path("ordinal").asInt(0));
            ps.setBoolean(8, row.path("visible").asBoolean(true));
            ps.setString(9, tenantId);
            ps.setString(10, seedKey);
            ps.setString(11, origin);
            ps.setString(12, seedFingerprint);
            ps.setString(13, overrideOf);
            ps.executeUpdate();
        }
    }

    private void updateReconciledRow(Connection connection, String menuTable, UUID id, JsonNode row, UUID parentId,
                                      String seedFingerprint) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + menuTable + " SET label = ?, target = ?, kind = ?, parent_menu_id = ?, required_role = ?, "
                        + "ordinal = ?, visible = ?, seed_fingerprint = ? WHERE id = ?")) {
            ps.setString(1, row.path("label").asText(""));
            ps.setString(2, row.path("target").asText(""));
            ps.setString(3, row.path("kind").asText("BUSINESS"));
            ps.setString(4, parentId == null ? null : parentId.toString());
            JsonNode requiredRole = row.get("requiredRole");
            ps.setString(5, requiredRole == null || requiredRole.isNull() ? null : requiredRole.asText());
            ps.setInt(6, row.path("ordinal").asInt(0));
            ps.setBoolean(7, row.path("visible").asBoolean(true));
            ps.setString(8, seedFingerprint);
            ps.setObject(9, id);
            ps.executeUpdate();
        }
    }

    // D7's "flagged override": a plain, seedKey-less, invisible row (never matched or touched by a
    // future reconcile pass) carrying the CURRENT -- about to be overwritten -- content of a
    // generated row the app author had edited, so their prior value is never silently lost. Visible
    // in generic CRUD / the Manager (WHERE override_of IS NOT NULL) even though it renders no nav
    // entry, which is exactly how "the Manager shows the divergence" reaches an operator without
    // this task building new Manager UI.
    private void cloneAsPreservedOverride(Connection connection, String menuTable, ExistingMenuRow existing, String overrideOf) throws Exception {
        String prefix = "(preserved edit) ";
        String label = existing.label() == null ? "" : existing.label();
        if (prefix.length() + label.length() > 120) {
            label = label.substring(0, Math.max(0, 120 - prefix.length()));
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + menuTable + " (id, label, target, kind, parent_menu_id, required_role, ordinal, "
                        + "visible, tenant_id, seed_key, seed_origin, seed_fingerprint, override_of) "
                        + "VALUES (?, ?, ?, ?, NULL, ?, ?, FALSE, ?, NULL, ?, NULL, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, prefix + label);
            ps.setString(3, existing.target());
            ps.setString(4, existing.kind());
            ps.setString(5, existing.requiredRole());
            ps.setInt(6, existing.ordinal());
            ps.setString(7, tenantId);
            ps.setString(8, ORIGIN_USER);
            ps.setString(9, overrideOf);
            ps.executeUpdate();
        }
    }

    private void deleteMenuRow(Connection connection, String menuTable, UUID id) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + menuTable + " WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    private String findExistingFingerprint(Connection connection, String menuTable) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT target FROM " + menuTable + " WHERE tenant_id = ? AND kind = 'INTERNAL' AND target LIKE ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, FINGERPRINT_TARGET_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1).substring(FINGERPRINT_TARGET_PREFIX.length());
            }
        }
    }

    private void deleteExistingMenus(Connection connection, String menuTable) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + menuTable + " WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
        }
    }

    // A non-navigable marker row (kind INTERNAL, invisible) recording which declared-menu
    // fingerprint produced the current seed -- shell.js and the generic business UI both already
    // skip any row that doesn't resolve to a real link, so this is inert to every existing reader.
    private void insertFingerprintRow(Connection connection, String menuTable, String fingerprint) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + menuTable + " (id, label, target, kind, parent_menu_id, required_role, ordinal, visible, tenant_id) "
                        + "VALUES (?, ?, ?, 'INTERNAL', NULL, NULL, ?, FALSE, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, "");
            ps.setString(3, FINGERPRINT_TARGET_PREFIX + fingerprint);
            ps.setInt(4, -1);
            ps.setString(5, tenantId);
            ps.executeUpdate();
        }
    }

    // Always returns an ArrayNode so every caller can safely iterate elements. Guards against the
    // REG-189 hazard: Build-NpdevApp.ps1 writes workspace-menu-pages-seed.json via the same
    // `+=` / ConvertTo-Json pattern that collapses a single-element manifest to a bare object. The
    // writer now forces array serialization (-AsArray), but this stays permissive so an app built
    // before that fix still seeds correctly without regeneration -- JsonNode.forEach() on a bare
    // ObjectNode iterates its FIELD VALUES, not the row itself, which silently corrupted (rather
    // than merely emptied) the seeded row set.
    private JsonNode readSeedResource(String location, boolean required) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        if (!required && !resource.exists()) {
            return objectMapper.createArrayNode();
        }
        JsonNode raw;
        try (var inputStream = resource.getInputStream()) {
            raw = objectMapper.readTree(inputStream);
        }
        if (raw.isArray()) {
            return raw;
        }
        if (raw.isObject()) {
            return objectMapper.createArrayNode().add(raw);
        }
        throw new IllegalStateException(
                "WorkspaceMenuSeeder: seed resource " + location + " is neither a JSON array nor an object (found "
                        + raw.getNodeType() + ")");
    }

    // Used by the two legacy modes only (reconcile has its own insertReconciledRow). Still stamps
    // seed_key/seed_origin/seed_fingerprint (W1.2) so an app that later switches its
    // npdev.workspace.menu-seed.mode to reconcile does not find every existing row untracked --
    // every row inserted through this class, in any mode, is a platform-seeded row.
    private void insertMenuRow(Connection connection, String menuTable, JsonNode row, UUID id, UUID parentId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + menuTable + " (id, label, target, kind, parent_menu_id, required_role, ordinal, "
                        + "visible, tenant_id, seed_key, seed_origin, seed_fingerprint) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, row.path("label").asText(""));
            ps.setString(3, row.path("target").asText(""));
            ps.setString(4, row.path("kind").asText("BUSINESS"));
            // parentMenuId is declared as a soft-reference string field (maxLength 64), not a uuid
            // column, so it's written as the id's string form rather than via setObject(UUID).
            ps.setString(5, parentId == null ? null : parentId.toString());
            JsonNode requiredRole = row.get("requiredRole");
            ps.setString(6, requiredRole == null || requiredRole.isNull() ? null : requiredRole.asText());
            ps.setInt(7, row.path("ordinal").asInt(0));
            ps.setBoolean(8, row.path("visible").asBoolean(true));
            ps.setString(9, tenantId);
            ps.setString(10, seedKeyOf(row));
            ps.setString(11, ORIGIN_GENERATED);
            ps.setString(12, rowContentFingerprint(row));
            ps.executeUpdate();
        }
    }
}
