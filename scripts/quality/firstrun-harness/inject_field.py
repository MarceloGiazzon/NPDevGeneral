#!/usr/bin/env python3
"""Insert one field object into a named concept's fields array in a model.json file.

Shared by the first-run harness's change-a-field check (I2) and its your-first-app
check, both of which need to prove that "add a field to the model" is a real,
scriptable edit -- not a step only a human narrating a doc could perform.
"""
import json
import sys


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: inject_field.py <model.json> <concept-name> <field-json>", file=sys.stderr)
        return 2

    model_path, concept_name, field_json = sys.argv[1:4]
    field = json.loads(field_json)

    with open(model_path, encoding="utf-8") as f:
        model = json.load(f)

    for concept in model.get("concepts", []):
        if concept.get("name") != concept_name:
            continue
        fields = concept.setdefault("fields", [])
        if any(existing.get("name") == field.get("name") for existing in fields):
            print(f"field {field.get('name')!r} already exists on concept {concept_name!r}", file=sys.stderr)
            return 1
        fields.append(field)
        with open(model_path, "w", encoding="utf-8") as f:
            json.dump(model, f, indent=2)
            f.write("\n")
        print(f"added field {field.get('name')!r} to concept {concept_name!r}")
        return 0

    print(f"concept {concept_name!r} not found in {model_path}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
