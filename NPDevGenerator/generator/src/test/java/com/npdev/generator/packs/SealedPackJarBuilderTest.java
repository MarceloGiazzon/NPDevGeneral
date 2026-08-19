package com.npdev.generator.packs;

import com.npdev.kernel.abi.KernelAbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUILD-2 (BT-2's own "the linking" follow-on): live proof for {@link SealedPackJarBuilder} --
 * BT-2's own stated proof bar, ONE step further than {@code SealedPackBuilderTest} already went.
 * That test proved byte-identical emitted SOURCE and compiled {@code .class} bytes across two
 * independent seals of the real {@code identity} pack; this proves the same property survives
 * packaging into an actual {@code .jar} FILE -- "the jar is byte-identical across two independent
 * builds", the roadmap item's own literal done-when clause.
 */
class SealedPackJarBuilderTest {

    private static final Path IDENTITY_PACK_FILE =
            Path.of("..", "..", "NPDevContract", "packs", "identity", "pack.json").toAbsolutePath().normalize();

    @Test
    void sealsRealIdentityPackIntoARealJar_withReadableAbiManifest(@TempDir Path outDir) throws IOException {
        assertTrue(Files.isRegularFile(IDENTITY_PACK_FILE), "expected " + IDENTITY_PACK_FILE + " to exist");

        Path jarFile = outDir.resolve("identity-v1.jar");
        SealedPackJarBuilder.JarResult result = new SealedPackJarBuilder().sealToJar(IDENTITY_PACK_FILE, jarFile);

        assertEquals("identity", result.manifest().packId());
        assertEquals(1, result.manifest().packMajorVersion());
        assertEquals(KernelAbi.CURRENT_ABI_VERSION, result.manifest().kernelAbiVersion());
        assertTrue(Files.isRegularFile(jarFile), "expected a real jar file to exist at " + jarFile);

        try (JarFile jar = new JarFile(jarFile.toFile())) {
            // Five identity concepts (User, Role, UserRole, PasswordResetToken, UserRolePermission),
            // each a compiled .class -- proves real bytecode landed in the jar, not just sources.
            assertTrue(jar.stream().anyMatch(e -> e.getName().equals(
                    "com/npdev/pack/identity/v1/User.class")), "expected a compiled User.class entry");
            assertTrue(jar.stream().anyMatch(e -> e.getName().equals(
                    "META-INF/npdev-pack.properties")), "expected the sealed-pack ABI manifest entry");

            JarEntry propertiesEntry = jar.getJarEntry("META-INF/npdev-pack.properties");
            PackAbiManifest readBack;
            try (var in = jar.getInputStream(propertiesEntry)) {
                readBack = PackAbiManifest.readFrom(in);
            }
            assertEquals(result.manifest(), readBack, "manifest round-tripped through the jar must be identical");

            Manifest jarManifest = jar.getManifest();
            assertEquals("identity", jarManifest.getMainAttributes().getValue("Npdev-Pack-Id"));
            assertEquals(KernelAbi.CURRENT_ABI_VERSION,
                    jarManifest.getMainAttributes().getValue(new Attributes.Name("Npdev-Kernel-Abi-Version")));
        }
    }

    @Test
    void sealingTheSamePackTwiceIndependently_producesByteIdenticalJarFiles(
            @TempDir Path outDir1, @TempDir Path outDir2
    ) {
        Path jar1 = outDir1.resolve("identity-v1.jar");
        Path jar2 = outDir2.resolve("identity-v1.jar");

        new SealedPackJarBuilder().sealToJar(IDENTITY_PACK_FILE, jar1);
        new SealedPackJarBuilder().sealToJar(IDENTITY_PACK_FILE, jar2);

        byte[] bytes1 = readBytes(jar1);
        byte[] bytes2 = readBytes(jar2);
        assertEquals(bytes1.length, bytes2.length, "two independently-built jars must be the same size");
        assertArrayEquals(bytes1, bytes2,
                "two fully independent seal-to-jar builds of the same pack input must be byte-identical");
    }

    @Test
    void refusesUnsealedPack_andWritesNoJar(@TempDir Path tempDir, @TempDir Path outDir) throws IOException {
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
                """);

        Path jarFile = outDir.resolve("orders-v1.jar");
        PackNotSealedException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                PackNotSealedException.class,
                () -> new SealedPackJarBuilder().sealToJar(unsealedPackFile, jarFile));
        assertTrue(thrown.getMessage().contains("orders"));
        assertTrue(Files.notExists(jarFile), "a refused seal must write no jar file");
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
