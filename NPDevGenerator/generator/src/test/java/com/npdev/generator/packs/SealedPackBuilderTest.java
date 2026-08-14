package com.npdev.generator.packs;

import com.npdev.kernel.abi.KernelAbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * BT-2 (PACK-ROADMAP.md, Track B): live proof for {@link SealedPackBuilder} against the REAL built-in
 * {@code identity} pack ({@code NPDevContract/packs/identity/pack.json}) -- the card's own steps
 * 1-4, and the card's own stated proof bar for step 6 ("the same sealed pack, consumed by two
 * different apps, produces byte-identical class files").
 *
 * <p>The "two different apps" half of that proof is modeled here as two wholly independent {@link
 * SealedPackBuilder#seal} invocations into two separate temp roots -- this is what actually varies
 * between two real apps consuming the same sealed pack (each has its own build, its own temp/output
 * directories, potentially its own package namespace) MINUS the app-linking wiring (BT-2 step 4,
 * {@code @ComponentScan}/{@code @EntityScan}), which is deliberately not attempted here (see BT-2's
 * ledger item). What this proves is the property that makes such linking safe to build later: sealed
 * pack emission is fully deterministic and independent of anything app-specific.
 */
class SealedPackBuilderTest {

    private static final Path IDENTITY_PACK_FILE =
            Path.of("..", "..", "NPDevContract", "packs", "identity", "pack.json").toAbsolutePath().normalize();

    @Test
    void sealsRealIdentityPack_intoOwnNamespace_notTheAppNamespace(@TempDir Path outputRoot) {
        assertTrue(Files.isRegularFile(IDENTITY_PACK_FILE), "expected " + IDENTITY_PACK_FILE + " to exist");

        SealedPackBuilder.SealResult result = new SealedPackBuilder().seal(IDENTITY_PACK_FILE, outputRoot);

        assertEquals("identity", result.manifest().packId());
        assertEquals(1, result.manifest().packMajorVersion());
        assertEquals(KernelAbi.CURRENT_ABI_VERSION, result.manifest().kernelAbiVersion());
        assertEquals("com.npdev.pack.identity.v1", result.manifest().packageName());

        // Five concepts declared in identity/pack.json: User, Role, UserRole, PasswordResetToken,
        // UserRolePermission -- all five must be sealed and emitted, none silently dropped.
        assertEquals(5, result.concepts().size());

        Path userEntity = outputRoot.resolve("src/main/java/com/npdev/pack/identity/v1/User.java");
        assertTrue(Files.isRegularFile(userEntity), "expected " + userEntity + " to exist");
        String source = readString(userEntity);
        assertTrue(source.contains("package com.npdev.pack.identity.v1;"),
                "expected pack-own package declaration, got:\n" + source);
        assertTrue(source.contains("public class User {"),
                "expected bare class name 'User' (package already disambiguates, no alias/pack prefix "
                        + "baked into the class name), got:\n" + source);
        assertFalse(source.contains("IdentityUser"),
                "class name must not embed the pack id -- the package already does that job");

        // BT-2 step 3: "verify the table name needs nothing" -- PK-2 already derives the physical
        // table name from packId+majorVersion, independent of any alias. Sealing must not need to (and
        // must not) touch it. identity's User table is identity_v1_users regardless of who seals it.
        assertTrue(source.contains("@Table(name = \"identity_v1_users\")"),
                "expected PK-2's alias-independent physical table name unchanged by sealing, got:\n" + source);

        Path userRepository = outputRoot.resolve("src/main/java/com/npdev/pack/identity/v1/UserRepository.java");
        assertTrue(Files.isRegularFile(userRepository), "expected " + userRepository + " to exist");
        String repoSource = readString(userRepository);
        assertTrue(repoSource.contains("package com.npdev.pack.identity.v1;"));
        assertTrue(repoSource.contains("import com.npdev.pack.identity.v1.User;"));
        assertTrue(repoSource.contains("interface UserRepository extends JpaRepository<User, UUID>"));

        Path manifestFile = outputRoot.resolve("META-INF").resolve("npdev-pack.properties");
        assertTrue(Files.isRegularFile(manifestFile), "expected " + manifestFile + " to exist");
    }

    @Test
    void refusesUnsealedPack_andWritesNothing(@TempDir Path tempDir, @TempDir Path outputRoot) throws IOException {
        Path unsealedPackFile = tempDir.resolve("unsealed-pack.json");
        Files.writeString(unsealedPackFile, """
                {
                  "dslVersion": "1.0.0",
                  "pack": "orders",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "placedByUserId", "type": "reference", "required": true,
                        "reference": { "target": "identity::User", "onDelete": "restrict" } }
                    ]}
                  ]
                }
                """, StandardCharsets.UTF_8);

        PackNotSealedException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                PackNotSealedException.class, () -> new SealedPackBuilder().seal(unsealedPackFile, outputRoot));
        assertTrue(thrown.getMessage().contains("orders"));
        assertTrue(thrown.getMessage().contains("identity::User"));

        try (Stream<Path> children = Files.exists(outputRoot) ? Files.list(outputRoot) : Stream.empty()) {
            assertEquals(0, children.count(), "a refused seal must write nothing");
        }
    }

    /**
     * BT-2's own stated proof bar for step 6. Seals the SAME real identity pack file twice,
     * independently, into two separate roots -- proving (a) the emitted SOURCE is byte-identical, and
     * (b) compiling each independently (in-process javac, jakarta.persistence-api only on the
     * classpath -- identity has no JSON-typed fields, so entity.mustache never emits the Hibernate
     * imports that would need a heavier classpath) produces byte-identical .class files.
     *
     * <p>Only the entity layer is compiled to .class here -- {@code JpaRepository} requires
     * spring-data-jpa on the compile classpath, a materially heavier dependency this slice does not
     * add just for this proof; the repository layer's byte-identical claim is proven at the SOURCE
     * level instead (identical source compiled by the identical javac version produces identical
     * bytecode -- the same reasoning check-deterministic-generation.ps1 already relies on for the
     * rest of the generator's own output).
     */
    @Test
    void sealingTheSamePackTwiceIndependently_producesByteIdenticalSourceAndClasses(
            @TempDir Path root1, @TempDir Path root2, @TempDir Path classesDir1, @TempDir Path classesDir2
    ) throws Exception {
        SealedPackBuilder.SealResult first = new SealedPackBuilder().seal(IDENTITY_PACK_FILE, root1);
        SealedPackBuilder.SealResult second = new SealedPackBuilder().seal(IDENTITY_PACK_FILE, root2);

        List<Path> firstFiles = listJavaFilesSorted(root1);
        List<Path> secondFiles = listJavaFilesSorted(root2);
        assertEquals(
                firstFiles.stream().map(root1::relativize).map(Path::toString).collect(Collectors.toList()),
                secondFiles.stream().map(root2::relativize).map(Path::toString).collect(Collectors.toList()),
                "the two independent seals must emit the identical set of relative file paths");

        for (int i = 0; i < firstFiles.size(); i++) {
            String relative = root1.relativize(firstFiles.get(i)).toString();
            String a = readString(firstFiles.get(i));
            String b = readString(secondFiles.get(i));
            assertEquals(a, b, "source must be byte-identical for " + relative);
        }

        // Real .class bytes, real javac, for every emitted entity file (not just User).
        List<Path> entityFiles1 = firstFiles.stream()
                .filter(p -> !p.getFileName().toString().endsWith("Repository.java"))
                .collect(Collectors.toList());
        List<Path> entityFiles2 = secondFiles.stream()
                .filter(p -> !p.getFileName().toString().endsWith("Repository.java"))
                .collect(Collectors.toList());
        assertEquals(entityFiles1.size(), entityFiles2.size());
        assertTrue(entityFiles1.size() >= 5, "expected at least the 5 identity concepts");

        compile(entityFiles1, classesDir1);
        compile(entityFiles2, classesDir2);

        List<Path> classes1 = listClassFilesSorted(classesDir1);
        List<Path> classes2 = listClassFilesSorted(classesDir2);
        assertEquals(classes1.size(), classes2.size(), "same number of compiled .class files");
        assertTrue(classes1.size() >= 5);

        for (int i = 0; i < classes1.size(); i++) {
            String relative = classesDir1.relativize(classes1.get(i)).toString();
            byte[] bytesA = readBytes(classes1.get(i));
            byte[] bytesB = readBytes(classes2.get(i));
            assertArrayEquals(bytesA, bytesB, "compiled .class bytes must be byte-identical for " + relative);
        }
    }

    private static void compile(List<Path> javaFiles, Path outDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "no system Java compiler available (need a JDK, not a JRE)");
        String classpath = System.getProperty("java.class.path");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        List<String> args = new java.util.ArrayList<>(List.of(
                "-d", outDir.toString(),
                "-classpath", classpath,
                "-proc:none"
        ));
        for (Path file : javaFiles) {
            args.add(file.toString());
        }
        int exit = compiler.run(null, new PrintStream(diagnostics), new PrintStream(diagnostics),
                args.toArray(new String[0]));
        if (exit != 0) {
            fail("javac failed (exit " + exit + "):\n" + diagnostics.toString(StandardCharsets.UTF_8));
        }
    }

    private static List<Path> listJavaFilesSorted(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    private static List<Path> listClassFilesSorted(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".class"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
