package com.npdev.generator.emitters.trustedsource.model;

/**
 * A field-level custom widget (kind {@code "widget"}): a single hash-locked, safety-scanned JS
 * file an author registers with {@code window.NpdevCustomWidgets.register(...)} and a field
 * opts into via {@code ui.widget: "custom"} + {@code ui.customWidgetRef: "<relativePath>"}.
 * Unlike a panel it is not itself a routed/access-controlled page -- it's a shared script
 * embedded inside whatever form already renders it, so it carries no route or requiredRole.
 * {@code relativePath} (e.g. {@code "widgets/star-rating.js"}) is used as-is for the on-disk
 * resource path, the served URL suffix, AND the manifest's {@code customWidgetRef} value the
 * client already has -- no separate slug/id mapping to keep in sync across client and server.
 */
public record TrustedWidget(
        String relativePath,
        String source
) {
}
