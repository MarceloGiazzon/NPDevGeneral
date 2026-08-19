package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.generator.testsupport.WorkspaceRootLocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 closeout C2 (finding C-D1). Pins {@code SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS}
 * against the platform columns {@link SchemaRealizationEmitter} actually appends.
 *
 * <p><b>Why the duplication is not "fixed" instead.</b> The RuntimeHost template cannot depend on
 * the generator module, so the executor's copy of the set is deliberate and stays. What was missing
 * was a test that fails loudly when the two drift -- the same drift class as the four
 * {@code model.schema.json} copies, which have a conformance test <i>because</i> they drifted twice.
 *
 * <p><b>What drift would cost.</b> {@code PLATFORM_MANAGED_COLUMNS} is subtracted from the
 * "unexplained extra column" set in {@code SchemaLifecycleExecutor#findSchemaAheadMissingColumns}'s
 * Trigger B. If the emitter grows a fifth platform column and the executor does not learn about it,
 * Trigger B sees a column it cannot explain and refuses a <b>healthy</b> boot.
 *
 * <p>The emitter side is computed by calling the real code path
 * ({@link SchemaRealizationEmitter#computeBusinessTableMetadata}) rather than by restating the
 * expected names, so this test tracks the emitter rather than a copy of it. The executor side is
 * parsed out of the Java source as text -- the same "assert against a path in the repo" approach
 * {@code MigrationAuthorityQuarantineAssertions} established, and the only way to read a constant
 * that lives in a module this one does not depend on.
 */
final class PlatformColumnContractTest {

    // BT-1: SchemaLifecycleExecutor is app-independent (no com.npdev.generated. reference) and now
    // lives in runtimehost-core, the app-independent half of RuntimeHost's source tree
    // (scripts/proofs/classify_runtimehost_sources.py), not the bridge.
    private static final Path EXECUTOR_SOURCE = Path.of(
            "NPDevRuntimeHost", "runtimehost-core", "src", "main", "java", "com", "finalexec", "db",
            "SchemaLifecycleExecutor.java");

    /** {@code private static final Set<String> PLATFORM_MANAGED_COLUMNS = Set.of("a", "b", ...);} */
    private static final Pattern DECLARATION = Pattern.compile(
            "PLATFORM_MANAGED_COLUMNS\\s*=\\s*Set\\.of\\(([^)]*)\\)\\s*;", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    @Test
    @DisplayName("C-D1: SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS must equal the platform "
            + "columns SchemaRealizationEmitter actually appends -- if they drift, Trigger B refuses "
            + "healthy boots")
    void platformManagedColumnsMatchesTheEmittersAppendedColumns() throws IOException {
        Set<String> emitted = platformColumnsTheEmitterAppends();
        Set<String> declaredByExecutor = platformManagedColumnsDeclaredBySchemaLifecycleExecutor();

        assertEquals(emitted, declaredByExecutor,
                "SchemaRealizationEmitter and SchemaLifecycleExecutor disagree about which columns the "
                        + "platform manages. Emitter appends: " + emitted + ". Executor declares: "
                        + declaredByExecutor + ". "
                        + fixInstruction(emitted, declaredByExecutor));
    }

    private static String fixInstruction(Set<String> emitted, Set<String> declared) {
        Set<String> missingFromExecutor = new LinkedHashSet<>(emitted);
        missingFromExecutor.removeAll(declared);
        Set<String> staleInExecutor = new LinkedHashSet<>(declared);
        staleInExecutor.removeAll(emitted);

        StringBuilder instruction = new StringBuilder();
        if (!missingFromExecutor.isEmpty()) {
            instruction.append("SchemaRealizationEmitter now emits platform column(s) ")
                    .append(missingFromExecutor)
                    .append(". Add them to SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS or Trigger B will "
                            + "treat them as unexplained extra columns and refuse healthy boots. ");
        }
        if (!staleInExecutor.isEmpty()) {
            instruction.append("SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS still lists ")
                    .append(staleInExecutor)
                    .append(", which the emitter no longer appends. Remove them, or Trigger B will silently "
                            + "excuse a genuinely unexplained column of that name.");
        }
        return instruction.toString();
    }

    /**
     * The columns the emitter adds that no concept declared. Built by running a one-concept model
     * with NO id field of its own through the emitter's real metadata computation, then subtracting
     * the concept's own declared field columns -- whatever is left is, by definition, platform-added.
     * The concept deliberately declares no id so the {@code !hasIdField} branch that injects
     * {@code id} is exercised.
     *
     * <p>R5.4: {@code softDelete: true} on this same fixture concept (rather than a second one) is
     * deliberate -- {@code deleted_at} is conditional on that flag (unlike version/row_version/
     * tenant_id, which every business table gets), so a fixture that never sets it would leave this
     * whole contract blind to a drift between {@code fullColumnNames}'s {@code deleted_at} branch and
     * {@code SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS} -- exactly the silent-drift class this
     * test exists to catch (see the class javadoc).
     */
    private static Set<String> platformColumnsTheEmitterAppends() {
        CompiledField title = new CompiledField("title", "string", "String", false, true, false);
        CompiledConcept note = new CompiledConcept(
                "Note", "Note", "notes", List.of(title), List.of(), List.of(), null, null, null, null,
                List.of(), null, null, null, null, true);
        CompiledModel model = new CompiledModel(
                "platform-column-contract", "1.0.0", "1.0.0", Map.of(note.getName(), note));

        SchemaRealizationEmitter.BusinessTableMetadata metadata =
                SchemaRealizationEmitter.computeBusinessTableMetadata(model);
        List<String> allColumns = metadata.businessTableColumns().get("notes");
        assertTrue(allColumns != null && !allColumns.isEmpty(),
                "the emitter must produce columns for the 'notes' table: " + metadata.businessTableColumns());

        Set<String> platformColumns = new LinkedHashSet<>(allColumns);
        for (CompiledField field : note.getFields()) {
            platformColumns.remove(SqlIdentifierSupport.columnName(field));
        }
        return platformColumns;
    }

    /** Parses the {@code Set.of(...)} literal out of the executor's source file. */
    private static Set<String> platformManagedColumnsDeclaredBySchemaLifecycleExecutor() throws IOException {
        Path source = WorkspaceRootLocator.resolveWorkspaceRoot().resolve(EXECUTOR_SOURCE);
        assertTrue(Files.isRegularFile(source),
                "expected to find the RuntimeHost template's SchemaLifecycleExecutor at " + source
                        + " -- if it moved, update PlatformColumnContractTest.EXECUTOR_SOURCE");

        String text = Files.readString(source);
        Matcher declaration = DECLARATION.matcher(text);
        assertTrue(declaration.find(),
                "could not find a 'PLATFORM_MANAGED_COLUMNS = Set.of(...)' declaration in " + source
                        + " -- if its shape changed, update PlatformColumnContractTest.DECLARATION");

        Set<String> columns = new LinkedHashSet<>();
        Matcher literal = STRING_LITERAL.matcher(declaration.group(1));
        while (literal.find()) {
            columns.add(literal.group(1));
        }
        assertTrue(!columns.isEmpty(),
                "parsed an empty PLATFORM_MANAGED_COLUMNS from " + source + " -- the regex is not matching "
                        + "the real declaration, which would make this contract test vacuously green");
        return columns;
    }
}
