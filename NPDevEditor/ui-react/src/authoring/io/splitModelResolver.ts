/**
 * Client-side (browser, File System Access API) port of NPDevCli's `resolve_split_model`
 * (npdev_cli.py) -- composes a model.json that splits its concepts/flows/etc. across sibling
 * `$ref`-linked files (e.g. `{"$ref": "concepts/Entidade.json"}`) into one plain object, the same
 * shape a non-split model.json already has.
 *
 * Kept deliberately in lockstep with the Python resolver rather than reinvented: same key lists,
 * same depth/cycle/file-count guards, same error message shapes. `packs` is passed through raw
 * (not composed) for the same reason the Python CLI leaves it alone -- pack-content composition
 * (namespacing, `as` aliasing) is a much larger feature that only the Java DSL resolver implements.
 *
 * MODEL_ARRAY_KEYS mirrors Python's list, which is the complete one -- CLAUDE.md documents that
 * Java's own copy has drifted stale (missing `documents`/`aggregates`/`autoPanels`/`selectors`),
 * so this ports the correct/complete source rather than either drifted copy.
 */

export const MODEL_ARRAY_KEYS = [
  "concepts",
  "domainTypes",
  "capabilities",
  "customCapabilities",
  "bindings",
  "events",
  "flows",
  "orchestrationRules",
  "orchestrations",
  "queries",
  "ruleProfiles",
  "procedures",
  "panels",
  "conversions",
  "documents",
  "guidePages",
  "aggregates",
  "autoPanels",
  "selectors",
  "roles",
  "propertyScopes",
  "properties",
  "contexts"
] as const;

export const ROOT_SCALAR_KEYS = [
  "$schema",
  "schemaVersion",
  "dslVersion",
  "namespace",
  "model",
  "version",
  "packs",
  "provides",
  "externalAi",
  "settings"
] as const;

const MODEL_ARRAY_KEY_SET = new Set<string>(MODEL_ARRAY_KEYS);
const FRAGMENT_KEY_SET = new Set<string>([...MODEL_ARRAY_KEYS, "metadata", "fragments"]);
const ROOT_KEY_SET = new Set<string>([...ROOT_SCALAR_KEYS, ...MODEL_ARRAY_KEYS, "metadata", "fragments"]);

const MAX_INCLUDE_DEPTH = 32;
const MAX_INCLUDED_FILES = 512;

export class SplitModelResolutionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SplitModelResolutionError";
  }
}

export type JsonObject = Record<string, unknown>;

type Loc = {
  dirHandle: FileSystemDirectoryHandle;
  dirSegments: string[];
};

type ResolveContext = {
  seenPaths: Set<string>;
};

function isPlainObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function refValue(value: unknown, label: string): string | null {
  if (!isPlainObject(value) || !("$ref" in value)) {
    return null;
  }
  const keys = Object.keys(value);
  if (keys.length !== 1 || keys[0] !== "$ref") {
    throw new SplitModelResolutionError(`${label}: $ref object must be exactly { "$ref": "relative/path.json" }`);
  }
  const ref = value.$ref;
  if (typeof ref !== "string" || !ref.trim()) {
    throw new SplitModelResolutionError(`${label}: $ref must be a non-blank string`);
  }
  return ref;
}

function validateRefs(value: unknown, label: string): void {
  if (Array.isArray(value)) {
    value.forEach((child, index) => validateRefs(child, `${label}/${index}`));
    return;
  }
  if (isPlainObject(value)) {
    refValue(value, label);
    for (const [childKey, childValue] of Object.entries(value)) {
      if (childKey === "packs") {
        continue;
      }
      validateRefs(childValue, `${label}/${childKey}`);
    }
  }
}

function normalizeRefSegments(ref: string, label: string): string[] {
  const normalized = ref.replace(/\\/g, "/");
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(normalized)) {
    throw new SplitModelResolutionError(`${label}: model include ref must be local, not a URL: ${ref}`);
  }
  if (normalized.startsWith("/")) {
    throw new SplitModelResolutionError(`${label}: model include ref must be relative: ${ref}`);
  }
  if (!normalized.toLowerCase().endsWith(".json")) {
    throw new SplitModelResolutionError(`${label}: model include ref must point to a .json file: ${ref}`);
  }
  const segments = normalized.split("/").filter((segment) => segment.length > 0);
  if (segments.some((segment) => segment === "." || segment === "..")) {
    throw new SplitModelResolutionError(`${label}: referenced model fragment escapes the model root: ${ref}`);
  }
  return segments;
}

async function readJsonFile(fileHandle: FileSystemFileHandle, pathKey: string): Promise<unknown> {
  const file = await fileHandle.getFile();
  const text = await file.text();
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new SplitModelResolutionError(`${pathKey}: not valid JSON (${(error as Error).message})`);
  }
}

async function includeFile(
  loc: Loc,
  ref: string,
  label: string
): Promise<{ fileHandle: FileSystemFileHandle; childLoc: Loc; pathKey: string }> {
  const segments = normalizeRefSegments(ref, label);
  let dirHandle = loc.dirHandle;
  let dirSegments = loc.dirSegments;
  for (let index = 0; index < segments.length - 1; index += 1) {
    try {
      dirHandle = await dirHandle.getDirectoryHandle(segments[index]);
    } catch {
      throw new SplitModelResolutionError(`${label}: referenced model fragment not found: ${ref}`);
    }
    dirSegments = [...dirSegments, segments[index]];
  }
  const fileName = segments[segments.length - 1];
  let fileHandle: FileSystemFileHandle;
  try {
    fileHandle = await dirHandle.getFileHandle(fileName);
  } catch {
    throw new SplitModelResolutionError(`${label}: referenced model fragment not found: ${ref}`);
  }
  return { fileHandle, childLoc: { dirHandle, dirSegments }, pathKey: [...dirSegments, fileName].join("/") };
}

async function readFragment(
  loc: Loc,
  ref: string,
  label: string,
  depth: number,
  stack: string[],
  ctx: ResolveContext
): Promise<{ data: unknown; childLoc: Loc; pathKey: string }> {
  if (depth > MAX_INCLUDE_DEPTH) {
    throw new SplitModelResolutionError(`${label}: maximum model include depth exceeded: ${MAX_INCLUDE_DEPTH}`);
  }
  const { fileHandle, childLoc, pathKey } = await includeFile(loc, ref, label);
  if (stack.includes(pathKey)) {
    throw new SplitModelResolutionError(`${pathKey}: circular model include detected`);
  }
  if (!ctx.seenPaths.has(pathKey)) {
    ctx.seenPaths.add(pathKey);
    if (ctx.seenPaths.size > MAX_INCLUDED_FILES) {
      throw new SplitModelResolutionError(`${pathKey}: maximum model include file count exceeded: ${MAX_INCLUDED_FILES}`);
    }
  }
  const data = await readJsonFile(fileHandle, pathKey);
  validateRefs(data, pathKey);
  return { data, childLoc, pathKey };
}

async function resolveArray(
  key: string,
  values: unknown,
  loc: Loc,
  label: string,
  depth: number,
  stack: string[],
  ctx: ResolveContext
): Promise<unknown[]> {
  if (!Array.isArray(values)) {
    throw new SplitModelResolutionError(`${label}: ${key} must be an array`);
  }
  const out: unknown[] = [];
  for (let index = 0; index < values.length; index += 1) {
    const item = values[index];
    const itemLabel = `${label}/${key}/${index}`;
    const ref = refValue(item, itemLabel);
    if (ref === null) {
      out.push(item);
      continue;
    }
    const { data: child, childLoc, pathKey } = await readFragment(loc, ref, itemLabel, depth + 1, [...stack, label], ctx);
    if (isPlainObject(child) && Array.isArray(child[key])) {
      const nested = await resolveArray(key, child[key], childLoc, pathKey, depth + 1, [...stack, label], ctx);
      out.push(...nested);
    } else {
      out.push(child);
    }
  }
  return out;
}

function appendFragment(target: JsonObject, fragment: JsonObject, rootMetadataKeys: Set<string>, label: string): void {
  for (const key of MODEL_ARRAY_KEYS) {
    if (key in fragment) {
      const existing = (target[key] as unknown[] | undefined) ?? [];
      target[key] = [...existing, ...(fragment[key] as unknown[])];
    }
  }
  if ("metadata" in fragment) {
    const metadata = (target.metadata as JsonObject | undefined) ?? {};
    target.metadata = metadata;
    for (const [metaKey, metaValue] of Object.entries(fragment.metadata as JsonObject)) {
      if (rootMetadataKeys.has(metaKey)) {
        continue;
      }
      if (metaKey in metadata) {
        throw new SplitModelResolutionError(`${label}: duplicate fragment metadata key: ${metaKey}`);
      }
      metadata[metaKey] = metaValue;
    }
  }
}

async function resolveModelFragment(
  loc: Loc,
  ref: string,
  label: string,
  depth: number,
  stack: string[],
  ctx: ResolveContext
): Promise<JsonObject> {
  const { data: fragment, childLoc, pathKey } = await readFragment(loc, ref, label, depth, stack, ctx);
  if (!isPlainObject(fragment)) {
    throw new SplitModelResolutionError(`${pathKey}: model fragment must be an object`);
  }
  const unsupported = Object.keys(fragment).filter((key) => !FRAGMENT_KEY_SET.has(key));
  if (unsupported.length > 0) {
    throw new SplitModelResolutionError(`${pathKey}: unsupported model fragment key: ${[...unsupported].sort()[0]}`);
  }

  const resolvedFragment: JsonObject = {};
  for (const key of MODEL_ARRAY_KEYS) {
    if (key in fragment) {
      resolvedFragment[key] = await resolveArray(key, fragment[key], childLoc, pathKey, depth + 1, [...stack, pathKey], ctx);
    }
  }
  if ("metadata" in fragment) {
    if (!isPlainObject(fragment.metadata)) {
      throw new SplitModelResolutionError(`${pathKey}: metadata must be an object`);
    }
    resolvedFragment.metadata = { ...fragment.metadata };
  }

  const fragments = (fragment.fragments as unknown[] | undefined) ?? [];
  for (let index = 0; index < fragments.length; index += 1) {
    const nestedLabel = `${pathKey}/fragments/${index}`;
    const nestedRef = refValue(fragments[index], nestedLabel);
    if (nestedRef === null) {
      throw new SplitModelResolutionError(`${nestedLabel}: fragment entry must be a $ref object`);
    }
    const nestedFragment = await resolveModelFragment(childLoc, nestedRef, pathKey, depth + 1, [...stack, pathKey], ctx);
    appendFragment(resolvedFragment, nestedFragment, new Set(), pathKey);
  }
  return resolvedFragment;
}

/**
 * Composes `modelFileName` (default "model.json") inside `rootDirHandle` into one plain object,
 * following every `$ref` it and its fragments contain. The result has the same shape a non-split
 * model.json already has (e.g. `concepts` is a plain array of inline concept objects) -- callers
 * can hand it straight to the existing `toInternalModelDocument`/`parseJsonDocument` pipeline.
 */
export async function resolveSplitModelFromDirectory(
  rootDirHandle: FileSystemDirectoryHandle,
  modelFileName = "model.json"
): Promise<JsonObject> {
  const ctx: ResolveContext = { seenPaths: new Set([modelFileName]) };

  let rootFileHandle: FileSystemFileHandle;
  try {
    rootFileHandle = await rootDirHandle.getFileHandle(modelFileName);
  } catch {
    throw new SplitModelResolutionError(`${modelFileName} was not found in the chosen folder`);
  }

  const raw = await readJsonFile(rootFileHandle, modelFileName);
  if (!isPlainObject(raw)) {
    throw new SplitModelResolutionError(`${modelFileName}: root model must be an object`);
  }
  const unsupported = Object.keys(raw).filter((key) => !ROOT_KEY_SET.has(key));
  if (unsupported.length > 0) {
    throw new SplitModelResolutionError(`${modelFileName}: unsupported model top-level key: ${[...unsupported].sort()[0]}`);
  }
  validateRefs(raw, modelFileName);

  const resolved: JsonObject = {};
  for (const key of ROOT_SCALAR_KEYS) {
    if (key in raw) {
      resolved[key] = raw[key];
    }
  }

  const rootLoc: Loc = { dirHandle: rootDirHandle, dirSegments: [] };
  for (const key of MODEL_ARRAY_KEYS) {
    if (key in raw) {
      resolved[key] = await resolveArray(key, raw[key], rootLoc, modelFileName, 0, [modelFileName], ctx);
    }
  }

  if ("metadata" in raw && !isPlainObject(raw.metadata)) {
    throw new SplitModelResolutionError(`${modelFileName}: metadata must be an object`);
  }
  const rootMetadataKeys = isPlainObject(raw.metadata) ? new Set(Object.keys(raw.metadata)) : new Set<string>();

  const fragments = (raw.fragments as unknown[] | undefined) ?? [];
  for (let index = 0; index < fragments.length; index += 1) {
    const label = `${modelFileName}/fragments/${index}`;
    const ref = refValue(fragments[index], label);
    if (ref === null) {
      throw new SplitModelResolutionError(`${label}: fragment entry must be a $ref object`);
    }
    const fragment = await resolveModelFragment(rootLoc, ref, modelFileName, 1, [modelFileName], ctx);
    appendFragment(resolved, fragment, rootMetadataKeys, modelFileName);
  }

  if ("metadata" in raw) {
    resolved.metadata = { ...(resolved.metadata as JsonObject | undefined), ...(raw.metadata as JsonObject) };
  }

  return resolved;
}

/**
 * True if `document` (already parsed JSON, not yet resolved) still contains `{"$ref": ...}`
 * placeholders in any of the array keys a split model can spread them across -- i.e. it was
 * opened as a single file rather than through `resolveSplitModelFromDirectory`. Used to fail the
 * single-file import path fast with a clear message instead of silently producing unnamed
 * concepts (see the drag-and-drop-session white-screen bug this was written to fix).
 */
export function findUnresolvedRef(document: JsonObject): { key: string; ref: string } | null {
  for (const key of MODEL_ARRAY_KEY_SET) {
    const values = document[key];
    if (!Array.isArray(values)) {
      continue;
    }
    for (const item of values) {
      const ref = refValue(item, key);
      if (ref !== null) {
        return { key, ref };
      }
    }
  }
  return null;
}
