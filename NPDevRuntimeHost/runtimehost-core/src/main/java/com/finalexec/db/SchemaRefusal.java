package com.finalexec.db;

import javax.sql.DataSource;

/**
 * STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): appends the boot-residue
 * block to a lifecycle refusal's message. The block is the platform-computed answer to "what did
 * the boot that just refused already commit?": the enumerated committed steps, each marked
 * idempotent or not, and the {@link RefusalResidue.Verdict} with the reason phrased for the
 * operator who has to decide whether to retry.
 *
 * <p>The append NEVER fails the refusal it decorates: any failure reading the journal degrades to
 * the bare message with a single logged line -- the same defensive shape
 * {@code SchemaHistoryStore}'s broken-write-never-propagates discipline applies on the read side.
 */
public final class SchemaRefusal {

    private SchemaRefusal() {
    }

    /**
     * @return {@code message}, with the residue block appended when the journal can answer it —
     *         {@code message} itself, byte-identical, when it cannot.
     */
    public static String withResidue(String message, DataSource dataSource, String bootId) {
        try {
            RefusalResidue.Residue residue = RefusalResidue.forBoot(dataSource, bootId);
            if (residue.isEmpty()) {
                return message;
            }
            return message + render(residue);
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: could not read the boot residue for the refusal "
                    + "(degrading to the bare message): " + exception.getMessage());
            return message;
        }
    }

    /** The STOR-32 readback block, rendered standalone -- the SAME block a refusal would have appended
     *  (via {@link #withResidue}), for the surfaces that must answer "what did the refused boot commit?"
     *  without re-throwing it: {@code GET /api/admin/schema-migration/last-refusal} and
     *  {@code npdev db explain-refusal}. */
    public static String render(RefusalResidue.Residue residue) {
        StringBuilder block = new StringBuilder();
        block.append("\nB7:refusal_residue: this boot committed ")
                .append(residue.steps().size())
                .append(" step(s) before refusing:\n");
        for (int i = 0; i < residue.steps().size(); i++) {
            BootResidueJournal.CommittedStep step = residue.steps().get(i);
            block.append("  ")
                    .append(i + 1).append(' ')
                    .append(pad(step.stepName(), 24))
                    .append(" committed  ")
                    .append(step.idempotent() ? "idempotent" : "NOT idempotent")
                    .append(step.detail() == null ? "" : " (" + step.detail() + ")")
                    .append('\n');
        }
        block.append("Verdict: ").append(residue.verdict().name());
        if (residue.verdict() == RefusalResidue.Verdict.INSPECT_FIRST) {
            block.append(" -- re-running this boot will re-execute step(s) ")
                    .append(nonIdempotentOrdinals(residue))
                    .append("; inspect them before retrying");
        }
        return block.toString();
    }

    private static String nonIdempotentOrdinals(RefusalResidue.Residue residue) {
        StringBuilder names = new StringBuilder();
        for (BootResidueJournal.CommittedStep step : residue.steps()) {
            if (!step.idempotent()) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(step.stepName());
            }
        }
        return names.toString();
    }

    private static String pad(String text, int width) {
        if (text == null) {
            text = "";
        }
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }
}