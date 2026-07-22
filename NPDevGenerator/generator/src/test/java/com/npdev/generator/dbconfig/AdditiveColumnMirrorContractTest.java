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
 * LNCH-1 T2. Pins the {@code realisticAdditiveColumns} helper that BOTH RuntimeHost proof-matrix test
 * classes use against {@link SchemaRealizationEmitter}'s real {@code additiveColumnNames}.
 *
 * <p><b>Why this test exists.</b> Those two helpers are a second hand-mirror of the emitter, exactly
 * like {@code SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS} is (pinned by
 * {@link PlatformColumnContractTest}). Each hard-codes which platform columns are NOT additive, as
 * string literals in a {@code .filter(...)} chain. Until T2 both excluded {@code version} — correctly,
 * at the time — and when T2 made {@code version} additive-eligible, both had to be edited by hand.
 * Updating two copies with nothing pinning them just resets the drift clock, which is the failure
 * mode this feature has hit repeatedly.
 *
 * <p><b>What drift would cost.</b> These helpers decide what a fixture's
 * {@code businessTableAdditiveColumns} contains. If a fixture claims a column is non-additive while
 * production says it is (or the reverse), scenarios that believe they are constructing an UNKNOWN
 * item silently stop doing so — the hollowed-out-proof failure mode that hardening finding X-B2 was
 * raised for, and that this round found again in scenarios 24b, 25 and 27.
 *
 * <p><b>Scope, deliberately narrow.</b> This pins one thing: the set of platform columns each mirror
 * EXCLUDES must equal the set of platform columns the emitter does not seed into
 * {@code additiveColumnNames}. It does not attempt to verify the mirrors' bond handling (which is
 * parameterised per fixture, not hard-coded) or any other pass.
 */
final class AdditiveColumnMirrorContractTest {

    private static final List<Path> MIRROR_SOURCES = List.of(
            Path.of("NPDevRuntimeHost", "src", "test", "java", "com", "finalexec", "db",
                    "SchemaLifecycleExecutorProofMatrixTest.java"),
            Path.of("NPDevRuntimeHost", "src", "test", "java", "com", "finalexec", "db",
                    "SchemaLifecycleExecutorPostgresProofMatrixTest.java"));

    /**
     * The stream chain inside {@code realisticAdditiveColumns}:
     * {@code additive.put(entry.getKey(), entry.getValue().stream() ... .toList());}
     */
    private static final Pattern MIRROR_CHAIN = Pattern.compile(
            "additive\\.put\\(entry\\.getKey\\(\\),\\s*entry\\.getValue\\(\\)\\.stream\\(\\)(.*?)\\.toList\\(\\)\\);",
            Pattern.DOTALL);

    /** A hard-coded column-name exclusion: {@code .filter(column -> !"id".equalsIgnoreCase(column))} */
    private static final Pattern EXCLUDED_LITERAL = Pattern.compile(
            "!\"([^\"]*)\"\\.equalsIgnoreCase");

    @Test
    @DisplayName("T-B2 guard: each RuntimeHost proof matrix's realisticAdditiveColumns mirror must "
            + "exclude exactly the platform columns SchemaRealizationEmitter does NOT make additive")
    void additiveColumnMirrorsMatchTheEmitter() throws IOException {
        Set<String> platformColumns = platformColumnsTheEmitterAppends();
        Set<String> additivePlatformColumns = platformColumnsTheEmitterMakesAdditive();

        Set<String> expectedExclusions = new LinkedHashSet<>(platformColumns);
        expectedExclusions.removeAll(additivePlatformColumns);

        assertTrue(!expectedExclusions.isEmpty(),
                "sanity: the emitter is expected to leave at least the primary key out of the additive "
                        + "set. Platform columns: " + platformColumns + ", additive: " + additivePlatformColumns);

        for (Path relativeSource : MIRROR_SOURCES) {
            Set<String> mirrored = exclusionsDeclaredBy(relativeSource);
            assertEquals(expectedExclusions, mirrored,
                    "The additive-column mirror in " + relativeSource.getFileName() + " disagrees with "
                            + "SchemaRealizationEmitter#additiveColumnNames. The emitter makes these platform "
                            + "columns additive: " + additivePlatformColumns + ", so the mirror must exclude "
                            + "exactly " + expectedExclusions + " but excludes " + mirrored + ". "
                            + fixInstruction(expectedExclusions, mirrored, relativeSource));
        }
    }

    private static String fixInstruction(Set<String> expected, Set<String> mirrored, Path source) {
        Set<String> overExcluded = new LinkedHashSet<>(mirrored);
        overExcluded.removeAll(expected);
        Set<String> underExcluded = new LinkedHashSet<>(expected);
        underExcluded.removeAll(mirrored);

        StringBuilder instruction = new StringBuilder();
        if (!overExcluded.isEmpty()) {
            instruction.append("Remove the .filter(...) for ").append(overExcluded)
                    .append(" from realisticAdditiveColumns in ").append(source.getFileName())
                    .append(" -- the emitter DOES make ").append(overExcluded)
                    .append(" additive, so fixtures currently understate what self-heals and any scenario "
                            + "relying on that column to produce an UNKNOWN is testing nothing. ");
        }
        if (!underExcluded.isEmpty()) {
            instruction.append("Add a .filter(column -> !\"<name>\".equalsIgnoreCase(column)) for ")
                    .append(underExcluded).append(" to realisticAdditiveColumns in ")
                    .append(source.getFileName())
                    .append(" -- the emitter does NOT make it additive, so fixtures currently overstate what "
                            + "self-heals and may miss a real refusal.");
        }
        return instruction.toString();
    }

    /** Same construction as {@link PlatformColumnContractTest}: a concept declaring no id of its own,
     * so the platform-added columns are whatever the emitter produces minus the concept's own fields. */
    private static Set<String> platformColumnsTheEmitterAppends() {
        SchemaRealizationEmitter.BusinessTableMetadata metadata = noteTableMetadata();
        Set<String> platformColumns = new LinkedHashSet<>(metadata.businessTableColumns().get("notes"));
        platformColumns.removeAll(declaredFieldColumns());
        return platformColumns;
    }

    private static Set<String> platformColumnsTheEmitterMakesAdditive() {
        SchemaRealizationEmitter.BusinessTableMetadata metadata = noteTableMetadata();
        List<String> additive = metadata.businessTableAdditiveColumns().get("notes");
        assertTrue(additive != null && !additive.isEmpty(),
                "the emitter must produce an additive column list for 'notes': "
                        + metadata.businessTableAdditiveColumns());
        Set<String> additivePlatform = new LinkedHashSet<>(additive);
        additivePlatform.removeAll(declaredFieldColumns());
        return additivePlatform;
    }

    private static SchemaRealizationEmitter.BusinessTableMetadata noteTableMetadata() {
        CompiledConcept note = note();
        CompiledModel model = new CompiledModel(
                "additive-column-mirror-contract", "1.0.0", "1.0.0", Map.of(note.getName(), note));
        return SchemaRealizationEmitter.computeBusinessTableMetadata(model);
    }

    private static CompiledConcept note() {
        CompiledField title = new CompiledField("title", "string", "String", false, true, false);
        return new CompiledConcept("Note", "Note", "notes", List.of(title));
    }

    private static Set<String> declaredFieldColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (CompiledField field : note().getFields()) {
            columns.add(SqlIdentifierSupport.columnName(field));
        }
        return columns;
    }

    /** Parses the hard-coded column exclusions out of one proof matrix's mirror helper, as text --
     * the only way to read a test-source constant in a module this one does not depend on. */
    private static Set<String> exclusionsDeclaredBy(Path relativeSource) throws IOException {
        Path source = WorkspaceRootLocator.resolveWorkspaceRoot().resolve(relativeSource);
        assertTrue(Files.isRegularFile(source),
                "expected a RuntimeHost proof matrix at " + source
                        + " -- if it moved, update AdditiveColumnMirrorContractTest.MIRROR_SOURCES");

        Matcher chain = MIRROR_CHAIN.matcher(Files.readString(source));
        assertTrue(chain.find(),
                "could not find the realisticAdditiveColumns stream chain in " + source
                        + " -- if its shape changed, update AdditiveColumnMirrorContractTest.MIRROR_CHAIN "
                        + "rather than deleting this contract, or the mirror goes unpinned again");

        Set<String> excluded = new LinkedHashSet<>();
        Matcher literal = EXCLUDED_LITERAL.matcher(chain.group(1));
        while (literal.find()) {
            excluded.add(literal.group(1));
        }
        assertTrue(!excluded.isEmpty(),
                "parsed zero hard-coded exclusions from " + source + " -- the regex is not matching the real "
                        + "helper, which would make this contract vacuously green");
        return excluded;
    }
}
