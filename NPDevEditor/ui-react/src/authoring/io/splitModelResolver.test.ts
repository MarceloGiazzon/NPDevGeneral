import { describe, expect, it } from "vitest";
import {
  findUnresolvedRef,
  resolveSplitModelFromDirectory,
  SplitModelResolutionError
} from "./splitModelResolver";

/**
 * Minimal in-memory stand-in for FileSystemDirectoryHandle/FileSystemFileHandle -- the real File
 * System Access API isn't available in vitest/jsdom, but its shape (getDirectoryHandle,
 * getFileHandle, getFile) is exactly what the resolver calls, so a plain object tree implementing
 * that surface exercises the real resolution algorithm end to end.
 */
type FakeTree = { [name: string]: FakeTree | string };

function makeDirHandle(tree: FakeTree): FileSystemDirectoryHandle {
  return {
    async getDirectoryHandle(name: string) {
      const entry = tree[name];
      if (entry === undefined || typeof entry === "string") {
        throw new Error(`not a directory: ${name}`);
      }
      return makeDirHandle(entry);
    },
    async getFileHandle(name: string) {
      const entry = tree[name];
      if (typeof entry !== "string") {
        throw new Error(`not a file: ${name}`);
      }
      return {
        async getFile() {
          return new File([entry], name, { type: "application/json" });
        }
      } as unknown as FileSystemFileHandle;
    }
  } as unknown as FileSystemDirectoryHandle;
}

function json(value: unknown): string {
  return JSON.stringify(value);
}

describe("resolveSplitModelFromDirectory", () => {
  it("resolves a WmsOffice-shaped model: every concepts[] entry is a $ref to concepts/<Name>.json", async () => {
    const tree: FakeTree = {
      "model.json": json({
        dslVersion: "1.0.0",
        namespace: "wmsoffice.core",
        version: "1.0.0",
        concepts: [{ $ref: "concepts/Entidade.json" }, { $ref: "concepts/Armazem.json" }]
      }),
      concepts: {
        "Entidade.json": json({ name: "Entidade", fields: [{ name: "id", type: "uuid", id: true, required: true }] }),
        "Armazem.json": json({
          name: "Armazem",
          fields: [
            { name: "id", type: "uuid", id: true, required: true },
            { name: "entidadeId", type: "reference", required: true, reference: { target: "Entidade" } }
          ]
        })
      }
    };

    const resolved = await resolveSplitModelFromDirectory(makeDirHandle(tree));
    const concepts = resolved.concepts as Array<{ name: string }>;

    expect(concepts).toHaveLength(2);
    expect(concepts.map((c) => c.name)).toEqual(["Entidade", "Armazem"]);
    expect(findUnresolvedRef(resolved)).toBeNull();
  });

  it("splices a fragment file's own array back in when the fragment itself declares the same key as a list", async () => {
    const tree: FakeTree = {
      "model.json": json({ namespace: "sample", version: "1.0.0", concepts: [{ $ref: "concepts/group.json" }] }),
      concepts: {
        "group.json": json({
          concepts: [
            { name: "Author", fields: [] },
            { name: "Book", fields: [] }
          ]
        })
      }
    };

    const resolved = await resolveSplitModelFromDirectory(makeDirHandle(tree));
    const concepts = resolved.concepts as Array<{ name: string }>;
    expect(concepts.map((c) => c.name)).toEqual(["Author", "Book"]);
  });

  it("composes a root-level fragments[] entry across multiple array keys", async () => {
    const tree: FakeTree = {
      "model.json": json({
        namespace: "sample",
        version: "1.0.0",
        concepts: [{ name: "Root", fields: [] }],
        fragments: [{ $ref: "extra.json" }]
      }),
      "extra.json": json({
        concepts: [{ name: "FromFragment", fields: [] }],
        flows: [{ name: "SomeFlow" }]
      })
    };

    const resolved = await resolveSplitModelFromDirectory(makeDirHandle(tree));
    expect((resolved.concepts as Array<{ name: string }>).map((c) => c.name)).toEqual(["Root", "FromFragment"]);
    expect((resolved.flows as Array<{ name: string }>).map((f) => f.name)).toEqual(["SomeFlow"]);
  });

  it("leaves packs[] raw/unresolved even though it uses a wider $ref+as shape", async () => {
    const tree: FakeTree = {
      "model.json": json({
        namespace: "sample",
        version: "1.0.0",
        concepts: [],
        packs: [{ $ref: "packs/identity/pack.json", as: "identity" }]
      })
    };

    const resolved = await resolveSplitModelFromDirectory(makeDirHandle(tree));
    expect(resolved.packs).toEqual([{ $ref: "packs/identity/pack.json", as: "identity" }]);
  });

  it("rejects an unknown top-level key with a clear message", async () => {
    const tree: FakeTree = {
      "model.json": json({ namespace: "sample", version: "1.0.0", concepts: [], notARealKey: [] })
    };

    await expect(resolveSplitModelFromDirectory(makeDirHandle(tree))).rejects.toThrow(SplitModelResolutionError);
  });

  it("rejects a missing referenced fragment with a clear message instead of hanging or crashing", async () => {
    const tree: FakeTree = {
      "model.json": json({ namespace: "sample", version: "1.0.0", concepts: [{ $ref: "concepts/Missing.json" }] }),
      concepts: {}
    };

    await expect(resolveSplitModelFromDirectory(makeDirHandle(tree))).rejects.toThrow(/referenced model fragment not found/);
  });

  it("detects a circular fragment include instead of recursing forever", async () => {
    const tree: FakeTree = {
      "model.json": json({ namespace: "sample", version: "1.0.0", concepts: [], fragments: [{ $ref: "a.json" }] }),
      "a.json": json({ fragments: [{ $ref: "b.json" }] }),
      "b.json": json({ fragments: [{ $ref: "a.json" }] })
    };

    await expect(resolveSplitModelFromDirectory(makeDirHandle(tree))).rejects.toThrow(/circular model include/);
  });
});

describe("findUnresolvedRef", () => {
  it("flags a plain (single-file) parse of a split model so the caller can fail fast with a clear message", () => {
    const document = { concepts: [{ $ref: "concepts/Entidade.json" }] };
    expect(findUnresolvedRef(document)).toEqual({ key: "concepts", ref: "concepts/Entidade.json" });
  });

  it("returns null for an already-resolved (or never-split) model", () => {
    const document = { concepts: [{ name: "Author", fields: [] }] };
    expect(findUnresolvedRef(document)).toBeNull();
  });
});
