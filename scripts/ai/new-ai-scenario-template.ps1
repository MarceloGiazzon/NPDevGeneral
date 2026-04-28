[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [Parameter(Mandatory = $true)]
    [string]$ScenarioRoot,
    [string]$ScenarioId = "ai-template-scenario"
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
New-Item -ItemType Directory -Force -Path $ScenarioRoot | Out-Null
Write-NPDevJsonFile (Join-Path $ScenarioRoot "model.json") ([pscustomobject]@{
    dslVersion = "1.0.0"
    namespace = "ai.template"
    version = "1.0.0"
    concepts = @([pscustomobject]@{ name = "TemplateItem"; fields = @([pscustomobject]@{ name = "id"; type = "uuid"; id = $true; required = $true }) })
    procedures = @()
    panels = @()
})
Write-NPDevJsonFile (Join-Path $ScenarioRoot "config.json") ([pscustomobject]@{
    configVersion = "1.0"
    scenario = [pscustomobject]@{ name = $ScenarioId; outputRoot = "./Output" }
    generator = [pscustomobject]@{ cleanOutputBeforeGenerate = $true; emitRuntimeAssets = $true; emitUiAssets = $true }
})
Write-NPDevJsonFile (Join-Path $ScenarioRoot "expected-behavior.json") ([pscustomobject]@{
    schemaVersion = "ai-expected-behavior.v1"
    scenarioId = $ScenarioId
    comparisonMode = "exact"
    expectedClass = "PASS_EXACT"
    assertions = @([pscustomobject]@{ path = "observations.runtime.profile"; equals = "supported-core" })
})
Write-NPDevJsonFile (Join-Path $ScenarioRoot "scenario.manifest.json") ([pscustomobject]@{
    schemaVersion = "ai-scenario.v1"
    scenarioId = $ScenarioId
    kind = "template"
    files = [pscustomobject]@{ model = "model.json"; config = "config.json"; expectedBehavior = "expected-behavior.json" }
    runtime = [pscustomobject]@{ strictExecution = $true; surfaceProfile = "supported-core" }
    requestedSurfaces = @("supported-core")
    operations = @([pscustomobject]@{ type = "metadata"; target = "model" })
    expectedOutcome = [pscustomobject]@{ class = "PASS_EXACT"; mustPass = $true }
})
Write-NPDevOk ("AI scenario template created at " + (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $ScenarioRoot))
