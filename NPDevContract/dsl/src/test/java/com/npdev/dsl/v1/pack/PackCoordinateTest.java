package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PK-5 step 3: pure parsing, no filesystem, no network -- see PackCoordinate's own doc. */
class PackCoordinateTest {

    @Test
    void parsesOciCoordinateWithTag() {
        PackCoordinate coordinate = PackCoordinate.parse("oci://ghcr.io/npdev/identity:2.1.0");
        assertTrue(coordinate instanceof OciCoordinate);
        OciCoordinate oci = (OciCoordinate) coordinate;
        assertEquals("ghcr.io", oci.registry());
        assertEquals("npdev/identity", oci.repository());
        assertEquals("2.1.0", oci.reference());
        assertFalse(oci.referenceIsDigest());
    }

    @Test
    void parsesOciCoordinateWithPortedRegistryAndTag() {
        OciCoordinate oci = (OciCoordinate) PackCoordinate.parse("oci://registry.example.com:5000/org/repo:1.0");
        assertEquals("registry.example.com:5000", oci.registry());
        assertEquals("org/repo", oci.repository());
        assertEquals("1.0", oci.reference());
    }

    @Test
    void parsesOciCoordinateWithDigest() {
        String digest = "sha256:" + "a".repeat(64);
        OciCoordinate oci = (OciCoordinate) PackCoordinate.parse("oci://ghcr.io/npdev/identity@" + digest);
        assertEquals(digest, oci.reference());
        assertTrue(oci.referenceIsDigest());
    }

    @Test
    void rejectsOciCoordinateWithNoTagOrDigest() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PackCoordinate.parse("oci://ghcr.io/npdev/identity"));
        assertTrue(failure.getMessage().contains("tag"), failure.getMessage());
    }

    @Test
    void rejectsOciCoordinateWithMalformedDigest() {
        assertThrows(IllegalArgumentException.class,
                () -> PackCoordinate.parse("oci://ghcr.io/npdev/identity@sha256:notactuallyhex"));
    }

    @Test
    void rejectsOciCoordinateWithNoRepositoryPath() {
        assertThrows(IllegalArgumentException.class, () -> PackCoordinate.parse("oci://ghcr.io:2.1.0"));
    }

    @Test
    void parsesGitCoordinateWithNoSubpath() {
        GitCoordinate git = (GitCoordinate) PackCoordinate.parse("git+https://example.com/org/identity.git@v2.1.0");
        assertEquals("https", git.transport());
        assertEquals("example.com/org/identity.git", git.repoUrl());
        assertEquals("", git.subpath());
        assertEquals("v2.1.0", git.tag());
        assertEquals("https://example.com/org/identity.git", git.fullUrl());
    }

    @Test
    void parsesGitCoordinateWithSubpath() {
        GitCoordinate git = (GitCoordinate) PackCoordinate.parse(
                "git+https://example.com/org/monorepo.git//packs/identity@v2.1.0");
        assertEquals("example.com/org/monorepo.git", git.repoUrl());
        assertEquals("packs/identity", git.subpath());
        assertEquals("v2.1.0", git.tag());
    }

    @Test
    void parsesGitFileCoordinate() {
        GitCoordinate git = (GitCoordinate) PackCoordinate.parse("git+file:///D:/scratch/repo@v1.0.0");
        assertEquals("file", git.transport());
        assertEquals("/D:/scratch/repo", git.repoUrl());
        assertEquals("v1.0.0", git.tag());
        assertEquals("file:///D:/scratch/repo", git.fullUrl());
    }

    @Test
    void tagDelimiterIsTheLastAtNotTheFirst() {
        // ssh URLs legitimately contain an earlier "user@host" -- only the trailing @ is the tag.
        GitCoordinate git = (GitCoordinate) PackCoordinate.parse("git+ssh://git@example.com/org/repo.git@v3.0.0");
        assertEquals("git@example.com/org/repo.git", git.repoUrl());
        assertEquals("v3.0.0", git.tag());
    }

    @Test
    void rejectsGitCoordinateWithNoTag() {
        assertThrows(IllegalArgumentException.class,
                () -> PackCoordinate.parse("git+https://example.com/org/identity.git"));
    }

    @Test
    void rejectsGitCoordinateWithUnknownTransport() {
        assertThrows(IllegalArgumentException.class,
                () -> PackCoordinate.parse("git+ftp://example.com/org/identity.git@v1"));
    }

    @Test
    void rejectsGitCoordinateWithPathTraversalSubpath() {
        assertThrows(IllegalArgumentException.class,
                () -> PackCoordinate.parse("git+https://example.com/org/repo.git//../../etc@v1"));
    }

    @Test
    void rejectsCoordinateWithUnknownScheme() {
        assertThrows(IllegalArgumentException.class, () -> PackCoordinate.parse("npm://left-pad@1.0.0"));
    }

    @Test
    void rejectsBlankCoordinate() {
        assertThrows(IllegalArgumentException.class, () -> PackCoordinate.parse("   "));
    }

    @Test
    void rawPreservesTheOriginalString() {
        String raw = "git+https://example.com/org/identity.git@v2.1.0";
        assertEquals(raw, PackCoordinate.parse(raw).raw());
    }
}
