package com.npdev.dsl.v1.compiled;

/**
 * PROC-5/PROC-6: compiled form of {@code collaboration} ({@link com.npdev.dsl.v1.ast.CollaborationAst}) --
 * which collaboration surfaces (comment thread, attachments) a concept's records expose. Absent on the
 * compiled concept when the model declares none, so existing canonical JSON stays byte-identical.
 */
public record CompiledCollaboration(boolean comments, boolean attachments) {
}
