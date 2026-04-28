export type AuthoringCapabilityBinding = {
  capability: string;
  target: string;
  mode: string;
};

export type AuthoringPermissionDefaults = {
  defaultRole?: string;
  readonlyRoles?: string[];
  hiddenActions?: string[];
};

export type AuthoringConfigMetadata = {
  projectionPreset?: string;
  samplePresetLabel?: string;
  notes?: string;
  capabilityBindings?: AuthoringCapabilityBinding[];
  permissionDefaults?: AuthoringPermissionDefaults;
};

export type AuthoringConfigDocument = {
  $schema?: string;
  configVersion: string;
  scenario: {
    name: string;
    description?: string;
    outputRoot: string;
  };
  generator: {
    failIfModelMissing: boolean;
    failIfConfigMissing: boolean;
    cleanOutputBeforeGenerate: boolean;
    emitPluginAssets: boolean;
    emitRuntimeAssets: boolean;
    emitUiAssets: boolean;
  };
  bootstrap: {
    root: string;
    mergeStrategy: "clean-copy" | "robocopy-merge";
  };
  artifact: {
    root: string;
    generatedFolderName: string;
    libsFolderName: string;
    metaFolderName: string;
  };
  finalExec: {
    root: string;
    deleteBeforeMount: boolean;
  };
  database: {
    provider: "docker-postgres" | "postgres";
    host: string;
    port: number;
    database: string;
    username: string;
    password: string;
    adminDatabase: string;
    resetMode: "reset" | "preserve";
    containerName?: string;
  };
  runtime: {
    springProfile: string;
    serverPort: number;
    javaArgs: string[];
    gradleTask: "bootRun";
  };
  metadata?: AuthoringConfigMetadata;
};

export type AuthoringConfigValidationIssue = {
  severity: "error" | "warning";
  path: string;
  message: string;
};

export type AuthoringConfigSession = {
  sourceKey: string;
  sourceLabel: string;
  document: AuthoringConfigDocument;
  dirty: boolean;
  lastLoadedLabel: string;
};
