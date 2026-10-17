package com.npdev.dsl.v1.ast;

/**
 * PROC-5/PROC-6: per-concept record collaboration ({@code collaboration: { comments, attachments }}).
 * {@code comments} gives every record of the concept a comment thread (with {@code @mentions}) behind
 * the record's own read access; {@code attachments} additionally lets files be added to that thread.
 */
public record CollaborationAst(boolean comments, boolean attachments) {
}
