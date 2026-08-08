package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the conformance suite's container images to DIGESTS -- and proves the pinned form is one
 * Testcontainers can actually parse.
 *
 * <p><b>Why a test and not just a comment.</b> {@code storage/FULL_SUPPORT_PLAN.md} W1.1 puts pinning
 * before promoting the CI trigger, for one reason: a push-blocking gate on a moving tag cannot
 * distinguish "we broke it" from "the image changed". The pin is therefore load-bearing, and a
 * load-bearing string that nothing checks is one careless edit from being a tag again.
 *
 * <p><b>The second assertion is the one that would have bitten.</b> {@code repo:tag@sha256:...} is
 * valid Docker syntax, but whether {@code DockerImageName.parse} keeps the digest AND still reports
 * compatibility with {@code MySQLContainer}'s expected image name is a Testcontainers question, not a
 * Docker one. Getting it wrong would surface as a container failing to start in CI, minutes into a
 * job, on a machine nobody is watching -- so it is asserted here, in milliseconds, with no Docker.
 *
 * <p>This is a Tier A test: no database, no Docker, no network.
 */
@DisplayName("Conformance container images -- pinned to digests, and parseable")
class DialectContainerImagePinningTest {

    @Test
    @DisplayName("every image is pinned to a sha256 digest, not a moving tag")
    void everyImageIsDigestPinned() {
        Map<String, String> images = DialectTestSupport.containerImages();
        assertTrue(!images.isEmpty(), "no container images registered at all");
        for (Map.Entry<String, String> entry : images.entrySet()) {
            String image = entry.getValue();
            assertTrue(image.contains("@sha256:"),
                    "dialect '" + entry.getKey() + "' is pinned to '" + image + "', which is a MOVING "
                    + "tag. A conformance gate on a moving tag cannot tell a regression from an "
                    + "upstream image change. Re-resolve with "
                    + "scripts/quality/check-container-images-pinned.py --resolve.");
            String digest = image.substring(image.indexOf("@sha256:") + "@sha256:".length());
            assertEquals(64, digest.length(),
                    "dialect '" + entry.getKey() + "': a sha256 digest is 64 hex characters, got '"
                    + digest + "'");
            assertTrue(digest.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')),
                    "dialect '" + entry.getKey() + "': digest is not lower-case hex: " + digest);
        }
    }

    @Test
    @DisplayName("Testcontainers parses the pinned form and still matches each container type")
    void testcontainersAcceptsThePinnedForm() {
        // THE TEST THAT EARNED ITS KEEP. The first version of the pin used `mysql:8.4@sha256:...`,
        // which is valid Docker syntax and reads better. Testcontainers 1.21.4 splits on `@` only, so
        // the repository came back as "mysql:8.4" and MySQLContainer's own compatibility assertion
        // would have thrown AT CONTAINER CONSTRUCTION -- thirty seconds into a CI job, inside a
        // forked test JVM whose stdout Gradle does not forward. This assertion found it in 11ms.
        Map<String, String> expectedRepository = Map.of(
                "mysql", "mysql",
                "postgres", "postgres",
                "sqlserver", "mcr.microsoft.com/mssql/server");

        for (Map.Entry<String, String> entry : DialectTestSupport.containerImages().entrySet()) {
            DockerImageName parsed = DockerImageName.parse(entry.getValue());
            String expected = expectedRepository.get(entry.getKey());
            assertTrue(expected != null,
                    "dialect '" + entry.getKey() + "' has an image but no expected repository here -- "
                    + "the two halves of the mapping have drifted");
            assertEquals(expected, parsed.getUnversionedPart(),
                    "the pinned reference for '" + entry.getKey() + "' does not parse back to the "
                    + "repository Testcontainers will compare it against -- a `tag@digest` reference "
                    + "does exactly this, and the container would refuse the image at start time");
            // asCompatibleSubstituteFor is what MySQLContainer et al. call internally; if this throws
            // the container would refuse the image.
            parsed.asCompatibleSubstituteFor(expected).assertValid();
            assertTrue(parsed.getVersionPart().startsWith("sha256:"),
                    "the digest did not survive parsing for '" + entry.getKey() + "': "
                    + parsed.asCanonicalNameString());
        }
    }

    @Test
    @DisplayName("every pinned digest records the tag it came from, so it can be refreshed")
    void everyDigestRecordsItsTag() {
        // A digest with no recorded tag cannot be re-resolved without archaeology, which is how a
        // quarterly refresh becomes a permanent pin. --resolve reads these.
        assertEquals(DialectTestSupport.containerImages().keySet(),
                DialectTestSupport.containerImageTags().keySet(),
                "every pinned image must record the tag it was resolved from, and vice versa");
        for (Map.Entry<String, String> entry : DialectTestSupport.containerImageTags().entrySet()) {
            String tag = entry.getValue();
            String image = DialectTestSupport.containerImages().get(entry.getKey());
            String repository = image.substring(0, image.indexOf("@sha256:"));
            assertTrue(tag.startsWith(repository + ":"),
                    "dialect '" + entry.getKey() + "': the recorded tag '" + tag + "' is not a tag of "
                    + "the pinned repository '" + repository + "' -- refreshing from it would resolve "
                    + "a DIFFERENT image than the one pinned");
        }
    }

    @Test
    @DisplayName("h2 has no image on purpose, and that stays true")
    void h2HasNoImage() {
        // Not a formality: the first real CI run reported 13 failures per job because the parameter
        // source handed h2 to a container-only run. If h2 ever GAINS an image here, that filter's
        // reasoning silently changes.
        assertTrue(!DialectTestSupport.containerImages().containsKey("h2"),
                "h2 is the local backend and has no container image by design");
    }
}
