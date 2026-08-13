package com.npdev.dsl.v1;

import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-3: a pack may declare {@code requires: {roles, capabilities, network}} -- what it needs from
 * the composing app. Composition refuses when a requirement is unbound, naming the pack, the path
 * that reached it, and the exact missing key; the app declares what it satisfies via a root
 * {@code provides} object. Presence/binding only -- rewriting a pack's own internal role checks to
 * consume the app's concrete role name is PACK-9, still open.
 */
class PackRequiresBindingTest {

    @TempDir
    Path temp;

    @Test
    void unboundRequiredRoleRefusesNamingThePackPathAndKey() throws Exception {
        write("packs/billing/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "billing",
                  "version": "1.0.0",
                  "requires": { "roles": ["admin"] },
                  "concepts": [ { "name": "Invoice", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "requires.unbound.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/billing/pack.json" } ]
                }
                """);

        IOException thrown = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        String message = thrown.getMessage();
        assertTrue(message.contains("billing"), "must name the pack, got: " + message);
        assertTrue(message.contains("admin"), "must name the missing role, got: " + message);
        assertTrue(message.contains("provides.roles"), "must name the fix, got: " + message);
    }

    @Test
    void boundRequiredRoleSucceeds() throws Exception {
        write("packs/billing/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "billing",
                  "version": "1.0.0",
                  "requires": { "roles": ["admin"] },
                  "concepts": [ { "name": "Invoice", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "requires.bound.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "provides": { "roles": ["admin"] },
                  "packs": [ { "$ref": "packs/billing/pack.json" } ]
                }
                """);

        ResolvedModelSource source = new ModelSourceResolver().resolve(model);
        assertTrue(source.resolvedRoot().has("concepts"));
    }

    @Test
    void unboundCapabilityAndNetworkAlsoRefuse() throws Exception {
        write("packs/notifier/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "notifier",
                  "version": "1.0.0",
                  "requires": { "capabilities": ["sendMail"], "network": ["smtp.example.com"] },
                  "concepts": [ { "name": "Notification", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] } ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "requires.capnet.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "provides": { "capabilities": ["sendMail"] },
                  "packs": [ { "$ref": "packs/notifier/pack.json" } ]
                }
                """);

        IOException thrown = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(thrown.getMessage().contains("smtp.example.com"), "got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("provides.network"), "got: " + thrown.getMessage());
    }

    @Test
    void requiresOnAFragmentIsForbidden() throws Exception {
        write("packs/withFragment/fragment.json", """
                {
                  "requires": { "roles": ["admin"] }
                }
                """);
        write("packs/withFragment/pack.json", """
                {
                  "dslVersion": "1.0.0",
                  "pack": "withFragment",
                  "version": "1.0.0",
                  "fragments": [ { "$ref": "fragment.json" } ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "requires.fragment.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/withFragment/pack.json" } ]
                }
                """);

        assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
