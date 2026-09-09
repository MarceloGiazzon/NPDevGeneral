package com.npdev.generator.dbconfig;

import java.util.List;

/**
 * Facts about one external hosting target -- the Java twin of one entry in
 * {@code scripts/policy/hosting-targets.json} (H2), loaded on the classpath by
 * {@link HostTargetProfiles} the same way {@link DockerEngineProfile}/{@link DockerEngineProfiles}
 * load per-DB-engine facts from {@code npdev/engine-profiles.json}. Same reasoning: per-target facts
 * belong in a profile object, never in an {@code if} chain per target (H13 anchor).
 *
 * <p>Both copies of the underlying data (this classpath resource and the CLI-facing
 * {@code scripts/policy/hosting-targets.json}) must agree -- there is exactly one place a hosting
 * fact is authored (H2's snippet), and this file is a build-time copy of it, not a second opinion.
 */
public record HostTargetProfile(
        String id,
        String label,
        int rung,
        String requiresEngine,
        Integer appMemoryMb,
        Integer sleepsAfterMin,
        String databaseKind,
        boolean persistentDisk,
        String injectsPortVar,
        boolean terminatesTls,
        boolean needsAccount,
        String needsBinary,
        String fit,
        List<String> caveats
) {
    /** Whether this app's own baked-in engine satisfies the target's requirement (null = any). */
    public boolean engineMatches(String appEngineExternalName) {
        return requiresEngine == null || requiresEngine.equals(appEngineExternalName);
    }
}
