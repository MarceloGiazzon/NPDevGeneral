import fs from "node:fs";
import path from "node:path";
import process from "node:process";
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
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
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

const ajv = new Ajv2020({
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
  schemaPath,
  instancePath,
  errors
};

process.stdout.write(JSON.stringify(report, null, 2));
process.exit(passed ? 0 : 1);
