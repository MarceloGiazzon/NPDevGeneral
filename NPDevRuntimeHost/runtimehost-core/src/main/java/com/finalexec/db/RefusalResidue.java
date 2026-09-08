package com.finalexec.db;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): the readback half of the
 * boot-residue journal -- given a boot_id, the committed steps in order plus the retry verdict:
 * {@code RETRY_SAFE} when every committed step is declared idempotent, {@code INSPECT_FIRST}
 * otherwise, naming the non-idempotent step(s). This is the platform-computed answer to the row's
 * old workaround ("inspect before retrying" -- an instruction to a human to go and look).
 *
 * <p>The verdict is only as honest as the {@link BootResidueJournal.LifecycleStep#idempotent}
 * flags, which is why those flags live in the enum (declared per step, verified against each
 * pass's code) and never come from the database row.
 */
public final class RefusalResidue {

    public enum Verdict {
        /** Every committed step is declared idempotent -- re-running this boot converges by design. */
        RETRY_SAFE,
        /** At least one committed step is NOT idempotent -- re-running re-executes it; inspect first. */
        INSPECT_FIRST
    }

    /** The committed steps in execution order plus the verdict, ready to render. */
    public record Residue(List<BootResidueJournal.CommittedStep> steps, Verdict verdict) {

        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    /** Public, data-only view of one committed step -- the readback surfaces (the controlpanel
     *  controller and the CLI main, both OUTSIDE this package) cannot name the package-private
     *  {@link BootResidueJournal.CommittedStep} type, so {@link #forBoot}'s residue is reshaped here. */
    public record StepView(int ordinal, String stepName, boolean idempotent, String detail) {
    }

    /** The same steps, reshaped for a cross-package surface. */
    public static List<StepView> stepViews(Residue residue) {
        return residue.steps().stream()
                .map(step -> new StepView(step.ordinal(), step.stepName(), step.idempotent(), step.detail()))
                .toList();
    }

    private RefusalResidue() {
    }

    /** Reads the boot's committed steps and decides the verdict. Never throws: an unreadable or
     *  absent journal degrades to an EMPTY residue (a refusal must never fail because its residue
     *  could not be read); an empty residue's caller renders nothing and the message stands alone.
     */
    public static Residue forBoot(DataSource dataSource, String bootId) {
        List<BootResidueJournal.CommittedStep> steps =
                BootResidueJournal.committedSteps(dataSource, bootId);
        List<BootResidueJournal.CommittedStep> nonIdempotent = steps.stream()
                .filter(step -> !step.idempotent())
                .toList();
        Verdict verdict = nonIdempotent.isEmpty() ? Verdict.RETRY_SAFE : Verdict.INSPECT_FIRST;
        return new Residue(List.copyOf(steps), verdict);
    }

    /** STOR-32 readback: the boot_id of the most recently refused boot (a boot with a
     *  started-but-not-committed step), for {@code GET .../last-refusal} and the CLI's
     *  {@code explain-refusal}. Never throws -- degrades to empty, like {@link #forBoot}. */
    public static Optional<String> latestRefusedBootId(DataSource dataSource) {
        return BootResidueJournal.latestRefusedBootId(dataSource);
    }

    /** The name of the step that was IN FLIGHT when the refused boot stopped, for the same surfaces.
     *  {@link BootResidueJournal} stays package-private; the name string is all a cross-package
     *  surface needs. */
    public static Optional<String> refusingStepName(DataSource dataSource, String bootId) {
        return BootResidueJournal.stepInFlight(dataSource, bootId).map(Enum::name);
    }
}