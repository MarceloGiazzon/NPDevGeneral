import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

import modelSchema from "@npdev-schema";
import { importBundleFromFiles, toCanonicalModelDocument } from "./bundleIoService";

/**
 * R6 (MASTER-ROADMAP.md F1 -- live hazard): bundleIoService's export/import functions used to be
 * hardcoded object literals naming 16 of model.schema.json's 34 root keys. Opening a real
 * packs-bearing model.json (e.g. AppGen/apps/_official/WmsOffice, or this in-repo fixture) and
 * saving it silently dropped `packs` and every other unlisted section -- no error anywhere,
 * because packs are optional so the model still validates. This test is schema-driven rather than
 * a second hand-maintained key list, so it can't silently drift out of sync with the real schema
 * the way the original 16-key literal did.
 */

const samplesRoot = fileURLToPath(new URL("../../../../../NPDevSamples", import.meta.url));
const fixtureRoot = path.join(samplesRoot, "dsl-conformance-max", "Input");

function schemaRootKeys(): string[] {
  const properties = (modelSchema as { properties?: Record<string, unknown> }).properties ?? {};
  return Object.keys(properties);
}

describe("bundleIoService schema-driven loss detector", () => {
  it("preserves every schema root key the source model.json actually declares (packs included)", async () => {
    const sourceText = fs.readFileSync(path.join(fixtureRoot, "model.json"), "utf-8");
    const sourceDocument = JSON.parse(sourceText) as Record<string, unknown>;
    const configText = fs.readFileSync(path.join(fixtureRoot, "config.json"), "utf-8");

    expect(Object.prototype.hasOwnProperty.call(sourceDocument, "packs")).toBe(true);

    const modelFile = new File([sourceText], "model.json", { type: "application/json" });
    const configFile = new File([configText], "config.json", { type: "application/json" });

    const imported = await importBundleFromFiles(modelFile, configFile);
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;

    const exported = toCanonicalModelDocument(imported.bundle.model) as Record<string, unknown>;

    const rootKeys = schemaRootKeys();
    const sourceKeys = Object.keys(sourceDocument);
    const declaredSourceKeys = sourceKeys.filter((key) => rootKeys.includes(key));
    // Sanity: the fixture must actually exercise more than the old hardcoded 16-key allowlist,
    // or this test would pass vacuously without ever having caught the bug it exists to catch.
    expect(declaredSourceKeys.length).toBeGreaterThan(16);

    const dropped = declaredSourceKeys.filter(
      (key) => !Object.prototype.hasOwnProperty.call(exported, key)
    );
    expect(dropped).toEqual([]);
    expect(exported.packs).toEqual(sourceDocument.packs);
  });
});
