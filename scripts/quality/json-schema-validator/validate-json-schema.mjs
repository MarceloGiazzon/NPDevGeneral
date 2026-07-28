import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import Ajv from "ajv";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

function parseArgs(argv) {
  const args = {};
  for (let index = 2; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key.startsWith("--") || value === undefined) {
      throw new Error(`Invalid argument near ${key}`);
    }
    args[key.slice(2)] = value;
    index += 1;
  }
  return args;
}

function readJson(filePath) {
  // PowerShell's `-Encoding UTF8` writes a BOM (unlike utf8NoBOM); strip it so a caller upstream
  // that wrote a BOM'd temp file (e.g. run-external-ai-gate.ps1's per-mission Set-Content) doesn't
  // fail JSON.parse on a byte that isn't part of the JSON at all.
  const raw = fs.readFileSync(filePath, "utf8");
  return JSON.parse(raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw);
}

function normalizeError(error) {
  return {
    path: error.instancePath || "/",
    schemaPath: error.schemaPath || "",
    keyword: error.keyword || "",
    message: error.message || "",
    params: error.params || {}
  };
}

const args = parseArgs(process.argv);
if (!args.schema || !args.instance) {
  throw new Error("Provide --schema and --instance.");
}

const schemaPath = path.resolve(args.schema);
const instancePath = path.resolve(args.instance);
const schema = readJson(schemaPath);
const instance = readJson(instancePath);

// Ajv2020 (the Ajv build this validator originally hardcoded) only understands the 2020-12
// dialect: pointing it at a draft-07 schema (e.g. impact-report.schema.json,
// external-ai-*.schema.json) fails with "no schema with key or ref
// 'http://json-schema.org/draft-07/schema#'", not a validation error against the INSTANCE -- a
// scope gap in the validator itself, found running this against the new external-ai-* schemas.
// Select the AJV build from the schema's own declared $schema instead of assuming one dialect for
// every schema in the repo.
const declaredSchema = String(schema.$schema || "");
const AjvClass = declaredSchema.includes("draft-07") ? Ajv : Ajv2020;

const ajv = new AjvClass({
  allErrors: true,
  strict: false,
  validateFormats: true,
  allowUnionTypes: true
});
addFormats(ajv);

const validate = ajv.compile(schema);
const passed = validate(instance);
const errors = passed ? [] : (validate.errors || []).map(normalizeError);
const report = {
  status: passed ? "passed" : "failed",
  engine: "ajv",
  engineVersion: "8.20.0",
  schemaDialect: declaredSchema.includes("draft-07") ? "draft-07" : "2020-12",
  schemaPath,
  instancePath,
  errors
};

process.stdout.write(JSON.stringify(report, null, 2));
process.exit(passed ? 0 : 1);
