param(
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

$workspaceRoot = (Resolve-Path ".").Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "docker-linux-proof-tests-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}
$tmpRoot = Join-Path $workspaceRoot "scripts\reports\tmp\docker-linux-proof-tests"
if (Test-Path -LiteralPath $tmpRoot) {
    Remove-Item -LiteralPath $tmpRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

$assertions = @()
function Add-Assertion {
    param([string]$Name, [bool]$Passed, [string]$Message)
    $script:assertions += [pscustomobject]@{
        name = $Name
        passed = $Passed
        message = $Message
    }
    if (-not $Passed) {
        throw $Message
    }
}

function Read-Json {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

$missingDockerReportPath = Join-Path $tmpRoot "missing-docker-report.json"
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/run-docker-linux-proof.ps1 `
    -WorkspaceRoot $workspaceRoot `
    -RunId $RunId `
    -ReportPath $missingDockerReportPath `
    -DockerExecutable "__npdev_missing_docker_for_test__" `
    -BuildTimeoutSeconds 1 `
    -RunTimeoutSeconds 1 2>$null | Out-Null
$missingDockerExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
$missingDockerReport = Read-Json $missingDockerReportPath
$missingCodes = @($missingDockerReport.failures | ForEach-Object { [string]$_.code })
Add-Assertion -Name "missing-docker-exits-nonzero" -Passed ($missingDockerExit -ne 0) -Message "Docker/Linux proof accepted a missing Docker executable."
Add-Assertion -Name "missing-docker-report-failed" -Passed ([string]$missingDockerReport.overallStatus -eq "failed") -Message "Docker/Linux proof did not report failed for missing Docker."
Add-Assertion -Name "missing-docker-records-version-failure" -Passed ($missingCodes -contains "docker-version-failed") -Message "Docker/Linux proof did not record docker-version-failed."
Add-Assertion -Name "timeout-policy-reported" -Passed ($null -ne $missingDockerReport.timeoutPolicy -and [int]$missingDockerReport.timeoutPolicy.dockerBuildTimeoutSeconds -eq 1 -and [int]$missingDockerReport.timeoutPolicy.dockerRunTimeoutSeconds -eq 1) -Message "Docker/Linux proof report did not expose timeout policy."

$policy = Read-Json "scripts/policy/beta-release-gate-policy.json"
$scope = Read-Json "scripts/policy/beta0-scope.json"
$commandPolicy = Read-Json "scripts/policy/ai-command-policy.json"
$pathTraversalRequest = Read-Json "golden-ai-scenarios/command-policy-path-traversal/command-request.json"
$dockerRequiredReport = @($policy.requiredReports | Where-Object { [string]$_.path -eq "scripts/reports/out/docker-linux-parity-report.json" })
$runtimePreflightReport = @($policy.requiredReports | Where-Object { [string]$_.path -eq "scripts/reports/out/runtimehost-staged-jar-preflight-report.json" })
Add-Assertion -Name "docker-report-is-release-required" -Passed ($dockerRequiredReport.Count -eq 1) -Message "docker-linux-parity-report.json is not a release required report."
Add-Assertion -Name "runtime-preflight-report-is-release-required" -Passed ($runtimePreflightReport.Count -eq 1) -Message "runtimehost-staged-jar-preflight-report.json is not a release required report."
Add-Assertion -Name "docker-report-is-scope-blocking" -Passed (@($scope.blockingReports) -contains "docker-linux-parity-report.json") -Message "docker-linux-parity-report.json is not scope-blocking."
Add-Assertion -Name "runtime-preflight-report-is-scope-blocking" -Passed (@($scope.blockingReports) -contains "runtimehost-staged-jar-preflight-report.json") -Message "runtimehost-staged-jar-preflight-report.json is not scope-blocking."
Add-Assertion -Name "docker-policy-is-blocking" -Passed ([string]$policy.dockerLinuxEvidence -eq "blocking-release-evidence" -and [string]$scope.dockerLinuxEvidence -eq "blocking-release-evidence" -and [bool]$scope.dockerRequiredForBeta0) -Message "Docker/Linux policy is not marked blocking for Beta 0."
Add-Assertion -Name "path-traversal-scenario-is-cross-platform" -Passed ([string]$pathTraversalRequest.workingDirectory -eq "/" -and [string]$pathTraversalRequest.expectedErrorCode -eq "NPDEV_COMMAND_WORKDIR_OUTSIDE_SANDBOX") -Message "Command policy path traversal fixture must use a Linux/Windows outside-workspace path."
foreach ($allowedVariable in @("NPDEV_RUNTIMEHOST_LIBS_DIR", "NPDEV_LOCAL_CACHE_ROOT", "NPDEV_GRADLE_USER_HOME", "GRADLE_USER_HOME")) {
    Add-Assertion -Name ("controlled-runner-preserves-" + $allowedVariable.ToLowerInvariant()) -Passed (@($commandPolicy.minimalEnvironment.allowedVariables) -contains $allowedVariable) -Message ("Controlled runner minimal environment must preserve " + $allowedVariable + " for Docker/Linux cache isolation.")
}

$dockerfileText = Get-Content -Raw -LiteralPath "Dockerfile.ai-beta"
$linuxPowerShellPath = ($dockerfileText -match "mcr\.microsoft\.com/powershell:.+debian") -or
    ($dockerfileText -match "FROM\s+debian:12-slim" -and
        $dockerfileText -match "packages\.microsoft\.com/config/debian/12/packages-microsoft-prod\.deb" -and
        $dockerfileText -match "powershell=7\.4\.")
Add-Assertion -Name "dockerfile-provides-linux-powershell" -Passed $linuxPowerShellPath -Message "Dockerfile must provide pinned PowerShell 7.4 on a Linux image."

$proofScriptText = Get-Content -Raw -LiteralPath "scripts/quality/run-docker-linux-proof.ps1"
$syncRuntimeHostLibsText = Get-Content -Raw -LiteralPath "scripts/runtimehost/sync-runtimehost-libs.ps1"
Add-Assertion -Name "docker-run-quotes-process-arguments" -Passed ($proofScriptText -match "function Join-ProcessArguments" -and $proofScriptText -match '-ArgumentList \$argumentLine') -Message "Docker/Linux proof must quote the sh -lc command as one process argument."
Add-Assertion -Name "docker-run-uses-linux-shell" -Passed ($proofScriptText -match '"sh",\s*\r?\n\s*"-lc"') -Message "Docker/Linux proof must run the container gate through a Linux shell."
Add-Assertion -Name "docker-run-isolates-runtimehost-cache" -Passed ($proofScriptText -match "NPDEV_RUNTIMEHOST_LIBS_DIR=/tmp/npdev-runtimehost-libs") -Message "Docker/Linux proof must force a Linux-local RuntimeHost libs cache path."
Add-Assertion -Name "docker-run-removes-stale-runtimehost-report" -Passed ($proofScriptText -match "runtimehost-libs-sync-report\.json") -Message "Docker/Linux proof must remove stale RuntimeHost sync reports before the container gate."
Add-Assertion -Name "runtimehost-sync-uses-separator-independent-jar-discovery" -Passed ($syncRuntimeHostLibsText -match "build/libs" -and $syncRuntimeHostLibsText -match "No RuntimeHost jars were discovered") -Message "RuntimeHost libs sync must discover build/libs jars on Linux and fail closed on an empty jar set."

$report = [pscustomobject]@{
    schemaVersion = "npdev-docker-linux-proof-test-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-docker-linux-proof-tests.ps1"
    workspaceRoot = $workspaceRoot
    overallStatus = "passed"
    cases = @(
        [pscustomobject]@{
            name = "missing-docker-fails"
            exitCode = $missingDockerExit
            reportPath = "scripts/reports/tmp/docker-linux-proof-tests/missing-docker-report.json"
            overallStatus = [string]$missingDockerReport.overallStatus
        }
    )
    assertions = @($assertions)
}

$reportPath = "scripts/reports/out/docker-linux-proof-tests-report.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportPath) | Out-Null
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host ("Docker/Linux proof tests passed. Report: " + $reportPath)
