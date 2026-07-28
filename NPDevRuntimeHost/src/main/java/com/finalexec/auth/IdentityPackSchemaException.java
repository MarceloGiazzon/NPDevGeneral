package com.finalexec.auth;

import java.sql.SQLException;

/**
 * REG-39: thrown instead of swallowing when a {@link SQLException} is a genuine schema mismatch (see
 * {@link SqlSchemaErrors#isSchemaMismatch}), so callers deep in a "best-effort, never throws" helper
 * (e.g. {@code bumpTokenVersion}) can still let a schema problem propagate distinctly to whatever
 * outer handler decides how to report it, instead of it collapsing into an ordinary business-logic
 * negative (a 404 "not found", a silently-ignored update, a generic auth failure).
 */
public final class IdentityPackSchemaException extends RuntimeException {

    public IdentityPackSchemaException(SQLException cause) {
        super(cause.getMessage(), cause);
    }
}
