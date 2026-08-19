package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the adapter-registration consistency that has broken clean-CI builds three times
 * (mail-inproc/mail-smtp, document-render-*, file-store-objectstore): every adapter module
 * included in NPDevKernel/settings.gradle must appear in EVERY packaged-app proof test's
 * adapter-jar build list, unless consciously listed in KNOWN_NOT_PACKAGED below. On the dev
 * machine (and even on CI, via test order) a missing entry is masked by jars a previous
 * build/test left behind -- this test removes the masking. See
 * docs/ADAPTER_REGISTRATION_CHECKLIST.md.
 */
class AdapterRegistrationConsistencyTest {

    /**
     * Adapter modules deliberately NOT built for packaged-app proof runs (test-support or not
     * referenced by the RuntimeHost template's unconditional imports). Adding a NEW adapter here
     * instead of to the proof-test lists is a conscious decision -- record the reason in
     * docs/ADAPTER_REGISTRATION_CHECKLIST.md when you do.
     */
    private static final Set<String> KNOWN_NOT_PACKAGED = Set.of(
            "audit-inproc",
            "audit-postgres",
            "events-inproc",
            "eventstore-postgres",
            "flowinstance-inproc",
            "flowinstance-postgres",
            "idempotency-inproc",
            "idempotency-postgres",
            "messaging-http",
            "messaging-inproc",
            "postgres-test-support",
            "tracestore-postgres",
            "tracing-inproc");

    private static final List<String> PROOF_TESTS = List.of(
            "TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java",
            "HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest.java",
            "HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java");

    @Test
    void everySettingsGradleAdapterIsInEveryProofTestListOrConsciouslyExcluded() throws IOException {
        Path repoRoot = findRepoRoot();
        Set<String> declared = extract(repoRoot.resolve("NPDevKernel/settings.gradle"),
                Pattern.compile("include\\s+'adapters:([a-z0-9-]+)'"));
        assertTrue(declared.size() >= 30, "suspiciously few adapters parsed from settings.gradle: " + declared.size());
        for (String testFile : PROOF_TESTS) {
            Set<String> listed = extract(proofTestPath(repoRoot, testFile),
                    Pattern.compile("\":adapters:([a-z0-9-]+):jar\""));
            Set<String> missing = new TreeSet<>(declared);
            missing.removeAll(listed);
            missing.removeAll(KNOWN_NOT_PACKAGED);
            assertTrue(missing.isEmpty(),
                    testFile + " adapter-jar list is missing " + missing
                    + " -- add each as \":adapters:<name>:jar\" (alphabetical position), or add it to"
                    + " KNOWN_NOT_PACKAGED with a recorded reason. See docs/ADAPTER_REGISTRATION_CHECKLIST.md");
        }
    }

    @Test
    void theThreeProofTestListsAreIdentical() throws IOException {
        Path repoRoot = findRepoRoot();
        Set<String> reference = null;
        String referenceName = null;
        for (String testFile : PROOF_TESTS) {
            Set<String> listed = extract(proofTestPath(repoRoot, testFile),
                    Pattern.compile("\":adapters:([a-z0-9-]+):jar\""));
            if (reference == null) {
                reference = listed;
                referenceName = testFile;
                continue;
            }
            assertEquals(reference, listed,
                    "adapter-jar lists differ between " + referenceName + " and " + testFile);
        }
    }

    @Test
    void everyExclusionStillExistsInSettingsGradle() throws IOException {
        Path repoRoot = findRepoRoot();
        Set<String> declared = extract(repoRoot.resolve("NPDevKernel/settings.gradle"),
                Pattern.compile("include\\s+'adapters:([a-z0-9-]+)'"));
        Set<String> stale = new TreeSet<>(KNOWN_NOT_PACKAGED);
        stale.removeAll(declared);
        assertTrue(stale.isEmpty(), "KNOWN_NOT_PACKAGED names adapters that no longer exist: " + stale);
    }

    // Walk up from the working directory until a directory containing BOTH NPDevKernel/settings.gradle
    // and NPDevGenerator exists. Deliberately does NOT key on the folder name "NPDev_General" -- a
    // real clone is named NPDevGeneral and agent worktrees are nested (the exact LNCH-22 bug class).
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("NPDevKernel/settings.gradle"))
                    && Files.isDirectory(dir.resolve("NPDevGenerator"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repo root not found walking up from " + System.getProperty("user.dir"));
    }

    private static Path proofTestPath(Path repoRoot, String testFile) {
        return repoRoot.resolve(
                "NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/" + testFile);
    }

    private static Set<String> extract(Path file, Pattern pattern) throws IOException {
        assertTrue(Files.exists(file), "expected file not found: " + file);
        Set<String> names = new LinkedHashSet<>();
        Matcher m = pattern.matcher(Files.readString(file));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
