[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\csrf-posture-report.json"
}

<#
LNCH-4: structural regression guard for docs/architecture/CSRF_POSTURE.md's claim -- CSRF does not
apply because no generated FinalApp authenticates via an ambient browser-attached credential
(session cookie, cached Basic auth). If any of these patterns appear in NPDevRuntimeHost's own
source (the template every FinalApp is assembled from, NOT a generated app's own output), the
claim in that document must be revisited before the change ships.
#>
$runtimeHostMainRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main"
$buildTemplatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
Ensure-NPDevDirectory $runtimeHostMainRoot "RuntimeHost main source root"
Ensure-NPDevFile $buildTemplatePath "RuntimeHost build template"

$forbiddenPatterns = @(
    "getSession(",
    "HttpSession",
    "addCookie(",
    "CookieCsrfTokenRepository"
)

$violations = @()
$javaFiles = @(Get-ChildItem -LiteralPath $runtimeHostMainRoot -Recurse -Filter "*.java" -File)
foreach ($file in $javaFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($pattern in $forbiddenPatterns) {
        if ($content.Contains($pattern)) {
            $violations += [pscustomobject]@{
                file = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $file.FullName
                pattern = $pattern
            }
        }
    }
}

$buildTemplateText = Get-Content -LiteralPath $buildTemplatePath -Raw
if ($buildTemplateText.Contains("spring-boot-starter-security")) {
    $violations += [pscustomobject]@{
        file = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $buildTemplatePath
        pattern = "spring-boot-starter-security"
    }
}

$status = if ($violations.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    scannedJavaFileCount = $javaFiles.Count
    violations = $violations
    claimDocument = "docs/architecture/CSRF_POSTURE.md"
}
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk ("CSRF posture check passed: no ambient-credential auth pattern found across " + $javaFiles.Count + " RuntimeHost source file(s).")
    return
}

Write-NPDevWarn "CSRF posture check failed -- see docs/architecture/CSRF_POSTURE.md before proceeding."
foreach ($violation in $violations) {
    Write-NPDevWarn ("  " + $violation.file + " :: " + $violation.pattern)
}
throw "CSRF posture check failed: ambient-credential auth pattern(s) found. Revisit docs/architecture/CSRF_POSTURE.md's claim before shipping this change."
