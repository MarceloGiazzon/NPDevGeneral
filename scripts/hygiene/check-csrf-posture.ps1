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
LNCH-4 / SEC-1: structural regression guard for docs/architecture/CSRF_POSTURE.md's claim -- a
cookie-authenticated (ambient credential) state-changing request needs the X-NPDev-Request header.
Fails if (a) a server-side session / Spring Security CSRF pattern appears in NPDevRuntimeHost's own
source (the template every FinalApp is assembled from, NOT a generated app's own output), or
(b) JwtBearerAuthFilter reads cookies (getCookies() without referencing CSRF_HEADER. Either way the
claim in that document must be revisited before the change ships.
#>
$runtimeHostMainRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main"
$runtimeHostCoreMainRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\runtimehost-core\src\main"
$buildTemplatePath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle.template"
Ensure-NPDevDirectory $runtimeHostMainRoot "RuntimeHost main source root"
Ensure-NPDevDirectory $runtimeHostCoreMainRoot "runtimehost-core main source root"
Ensure-NPDevFile $buildTemplatePath "RuntimeHost build template"

$forbiddenPatterns = @(
    "getSession(",
    "HttpSession",
    "addCookie(",
    "CookieCsrfTokenRepository"
)

$violations = @()
$javaFiles = @(Get-ChildItem -LiteralPath $runtimeHostMainRoot -Recurse -Filter "*.java" -File) +
    @(Get-ChildItem -LiteralPath $runtimeHostCoreMainRoot -Recurse -Filter "*.java" -File)
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

# SEC-1: the npdev_jwt cookie is an ambient credential, so the filter that reads it must gate unsafe
# methods on the CSRF header.
$jwtFilterPath = Join-Path $runtimeHostMainRoot "java\com\finalexec\config\JwtBearerAuthFilter.java"
if (Test-Path -LiteralPath $jwtFilterPath) {
    $jwtFilterText = Get-Content -LiteralPath $jwtFilterPath -Raw
    if ($jwtFilterText.Contains("getCookies(") -and -not $jwtFilterText.Contains("CSRF_HEADER")) {
        $violations += [pscustomobject]@{
            file = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $jwtFilterPath
            pattern = "getCookies( without CSRF_HEADER"
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
