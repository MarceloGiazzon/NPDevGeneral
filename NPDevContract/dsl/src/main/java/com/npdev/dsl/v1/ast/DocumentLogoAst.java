package com.npdev.dsl.v1.ast;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): a document's logo, bound to a {@code field} on the document's
 * root concept (its {@code concept}, or the root concept of its {@code aggregate} when one is
 * declared) -- the SAME "name a property" binding every other document/band/panel field uses, never a
 * URL. This is a deliberate safety choice, not an incidental one: {@code
 * DocumentRenderInProcAdapter}'s renderer already refuses to fetch anything except inline {@code
 * data:} URIs while rendering (R6-F3, its {@code DENY_EXTERNAL_URIS} resolver) to close an SSRF hole
 * where a server-side PDF render could be made to reach internal hosts or cloud metadata endpoints. A
 * logo modeled as an author-supplied URL would reopen exactly that hole one field at a time, so the
 * model only ever lets a logo point at an existing stored property -- the caller resolves that
 * property's bytes and hands the adapter an already-inline {@code data:} URI, never a fetchable
 * address.
 */
public record DocumentLogoAst(String field) {
}
