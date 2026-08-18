package com.npdev.dsl.v1.xref;

/**
 * How confidently a {@link ReferenceEdge}'s target was identified.
 *
 * <p>{@link #UNDECIDABLE} is the load-bearing value and the reason this is a three-state enum
 * rather than a boolean. Some references genuinely cannot be resolved statically without an
 * expression evaluator -- an arbitrary {@code visibleWhen} predicate outside the interaction
 * grammar, a {@code $var.field} whose {@code $var} came out of a {@code capabilityCall} whose
 * output schema is declared elsewhere. Classifying those explicitly is the whole point: XREF-1's
 * origin (REG-185) is a validator that reported "passed" on a model referencing fields that do not
 * exist, precisely because "we could not check this" and "we checked this and it was fine" printed
 * identically. Letting UNDECIDABLE quietly become RESOLVED would rebuild that defect.
 *
 * <p>Consumers treat the three differently and deliberately:
 * <ul>
 *   <li>{@code ReferenceIntegrityValidation} (REG-185) raises an ERROR for {@link #UNRESOLVED}
 *       only. An UNDECIDABLE edge never blocks a build -- a checker that fails on what it cannot
 *       understand trains authors to work around it.</li>
 *   <li>{@code npdev inspect usage --orphans} (XREF-2) prints both, distinguishing them, and exits
 *       non-zero only for UNRESOLVED.</li>
 *   <li>{@code npdev migrate rename --cascade} (XREF-3) rewrites RESOLVED edges and REFUSES
 *       outright when any UNDECIDABLE edge names the field being renamed, rather than rewriting a
 *       file while knowingly leaving behind a reference it could not see.</li>
 * </ul>
 */
public enum Resolution {

    /** The target exists in the model; {@code toName} names it exactly. */
    RESOLVED,

    /** The reference is well-formed and its target kind is known, but no such target exists. */
    UNRESOLVED,

    /**
     * The reference could not be evaluated statically. Not a defect claim in either direction --
     * {@code toName} holds the raw text so a human (or a later evaluator) can judge it.
     */
    UNDECIDABLE
}
