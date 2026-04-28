import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import type { AuthoringModelDocument } from "../editors/modelDocumentTypes";

import mediumExpenseApprovalConfigJson from "@npdev-samples/medium-expense-approval/Input/config.json";
import mediumExpenseApprovalModelJson from "@npdev-samples/medium-expense-approval/Input/model.json";
import simpleContactIntakeConfigJson from "@npdev-samples/simple-contact-intake/Input/config.json";
import simpleContactIntakeModelJson from "@npdev-samples/simple-contact-intake/Input/model.json";
import simpleUserRegistryConfigJson from "@npdev-samples/simple-user-registry/Input/config.json";
import simpleUserRegistryModelJson from "@npdev-samples/simple-user-registry/Input/model.json";

export type CanonicalSampleEntry = {
  id: string;
  label: string;
  model: AuthoringModelDocument;
  config: AuthoringConfigDocument;
};

const CANONICAL_SAMPLE_REGISTRY = new Map<string, CanonicalSampleEntry>([
  [
    "simple-user-registry",
    {
      id: "simple-user-registry",
      label: "Official sample: simple-user-registry",
      model: simpleUserRegistryModelJson as AuthoringModelDocument,
      config: simpleUserRegistryConfigJson as AuthoringConfigDocument
    }
  ],
  [
    "simple-contact-intake",
    {
      id: "simple-contact-intake",
      label: "Official sample: simple-contact-intake",
      model: simpleContactIntakeModelJson as AuthoringModelDocument,
      config: simpleContactIntakeConfigJson as AuthoringConfigDocument
    }
  ],
  [
    "medium-expense-approval",
    {
      id: "medium-expense-approval",
      label: "Official sample: medium-expense-approval",
      model: mediumExpenseApprovalModelJson as AuthoringModelDocument,
      config: mediumExpenseApprovalConfigJson as AuthoringConfigDocument
    }
  ]
]);

export function getCanonicalSampleEntry(sampleId: string): CanonicalSampleEntry | null {
  return CANONICAL_SAMPLE_REGISTRY.get(sampleId) ?? null;
}
