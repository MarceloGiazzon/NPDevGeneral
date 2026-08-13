package com.finalexec.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPDev's first genuinely automatic (no human/curl trigger) data-seeding mechanism: on every
 * boot, if {@code workspace_menus} is empty for the configured tenant, inserts the default rows
 * BusinessUiEmitter derived at generation time from the app's own persisted concepts/declared
 * Panels, plus any hand-authored companion pages declared in definition/pages.json -- so both the
 * generic business UI and any app's own shell.js get a working nav with zero manual setup.
 * Self-disabling exactly like BootstrapAdminController: once any row exists, this never touches
 * the table again, so an app author's edits via generic CRUD are permanent.
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

    private final DataSource dataSource;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String tenantId;
    private final String seedMode;

    public WorkspaceMenuSeeder(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${npdev.workspace.menu-seed.tenant-id:dev}") String tenantId,
            @Value("${npdev.workspace.menu-seed.mode:insert-if-empty}") String seedMode
    ) {
        this.dataSource = dataSource;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? "dev" : tenantId.trim();
        this.seedMode = MODE_UPSERT_IF_FINGERPRINT_CHANGED.equals(seedMode == null ? null : seedMode.trim())
                ? MODE_UPSERT_IF_FINGERPRINT_CHANGED
                : MODE_INSERT_IF_EMPTY;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            List<JsonNode> rows = new ArrayList<>();
            readSeedResource("classpath:npdev-seed/workspace-menu-seed.json", true).forEach(rows::add);
            readSeedResource("classpath:npdev-seed/workspace-menu-pages-seed.json", false).forEach(rows::add);
            String fingerprint = computeFingerprint(rows);

            if (MODE_UPSERT_IF_FINGERPRINT_CHANGED.equals(seedMode)) {
                String existingFingerprint = findExistingFingerprint(connection);
                if (fingerprint.equals(existingFingerprint)) {
                    return; // already seeded from this exact declared menu -- nothing to do.
                }
                // Deliberately destructive: this mode is opt-in specifically to pick up authoring
                // changes on redeploy, which means any manual edit/addition an app author made
                // through generic CRUD since the last seed is wiped along with it. The default mode
                // below (insert-if-empty) is what protects those edits; switching to this one is a
                // documented, informed tradeoff (see WORKSPACE-MENU-AND-GUIDEPAGES.md).
                deleteExistingMenus(connection);
            } else if (countExistingMenus(connection) > 0) {
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
                insertMenuRow(connection, row, idByRow.get(i), parentId);
            }
            if (MODE_UPSERT_IF_FINGERPRINT_CHANGED.equals(seedMode)) {
                insertFingerprintRow(connection, fingerprint);
            }
            System.out.println("WorkspaceMenuSeeder: seeded " + rows.size()
                    + " workspace_menus row(s) for tenant '" + tenantId + "' (mode: " + seedMode + ").");
        }
    }

    private int countExistingMenus(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM workspace_menus WHERE tenant_id = ?")) {
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
        String canonicalJson = objectMapper.writeValueAsString(arrayNode);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String findExistingFingerprint(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT target FROM workspace_menus WHERE tenant_id = ? AND kind = 'INTERNAL' AND target LIKE ?")) {
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

    private void deleteExistingMenus(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM workspace_menus WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
        }
    }

    // A non-navigable marker row (kind INTERNAL, invisible) recording which declared-menu
    // fingerprint produced the current seed -- shell.js and the generic business UI both already
    // skip any row that doesn't resolve to a real link, so this is inert to every existing reader.
    private void insertFingerprintRow(Connection connection, String fingerprint) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO workspace_menus (id, label, target, kind, parent_menu_id, required_role, ordinal, visible, tenant_id) "
                        + "VALUES (?, ?, ?, 'INTERNAL', NULL, NULL, ?, FALSE, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, "");
            ps.setString(3, FINGERPRINT_TARGET_PREFIX + fingerprint);
            ps.setInt(4, -1);
            ps.setString(5, tenantId);
            ps.executeUpdate();
        }
    }

    private JsonNode readSeedResource(String location, boolean required) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        if (!required && !resource.exists()) {
            return objectMapper.createArrayNode();
        }
        try (var inputStream = resource.getInputStream()) {
            return objectMapper.readTree(inputStream);
        }
    }

    private void insertMenuRow(Connection connection, JsonNode row, UUID id, UUID parentId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO workspace_menus (id, label, target, kind, parent_menu_id, required_role, ordinal, visible, tenant_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
            ps.executeUpdate();
        }
    }
}
