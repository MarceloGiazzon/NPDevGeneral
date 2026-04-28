param(
    [string]$NPDevRoot = "",
    [string]$FinalExecRoot = "",
    [string[]]$ForbiddenTerms = @(
        "Patient",
        "Patients",
        "Appointment",
        "Appointments",
        "InsuranceClaim",
        "InsuranceClaims",
        "MedicationOrder",
        "MedicationOrders",
        "ExamRoom",
        "ExamRooms",
        "DraftInsuranceClaim",
        "AppointmentCompleted",
        "CompleteAppointmentFlow",
        "CreateAppointment",
        "appointment-scheduling",
        "healthcare",
        "clinic",
        "medical",
        "medication"
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\sample-common.ps1")

function Is-AllowedPath([string]$PathValue) {
    $normalized = $PathValue.Replace("/", "\").ToLowerInvariant()
    $allowedFileNames = @(
        "project_digest.md",
        "readme.md"
    )
    $allowedFragments = @(
        "\src\test\",
        "\npdev-template-library\",
        "\npdev-scenario-templates\",
        "\npdev-import-onboarding\",
        "\npdev-capability-marketplace\",
        "\node_modules\",
        "\.gradle\",
        "\build\",
        "\runtime-data\",
        "\libs\"
    )

    $fileName = [System.IO.Path]::GetFileName($normalized)
    if ($allowedFileNames -contains $fileName) {
        return $true
    }

    foreach ($fragment in $allowedFragments) {
        if ($normalized.Contains($fragment)) {
            return $true
        }
    }
    return $false
}

function Is-TextLikeFile([System.IO.FileInfo]$File) {
    $allowedExtensions = @(
        ".java",
        ".json",
        ".yml",
        ".yaml",
        ".properties",
        ".sql",
        ".js",
        ".css",
        ".html",
        ".txt",
        ".md",
        ".xml",
        ".gradle",
        ".ps1"
    )
    return $allowedExtensions -contains $File.Extension.ToLowerInvariant()
}

function Get-RelativePathCompat([string]$Root, [string]$PathValue) {
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    $pathFull = [System.IO.Path]::GetFullPath($PathValue)
    if ($pathFull.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $pathFull.Substring($rootFull.Length).TrimStart('\')
    }
    return $pathFull
}

if ([string]::IsNullOrWhiteSpace($FinalExecRoot)) {
    $samplesRoot = Normalize-AbsolutePath (Join-Path $PSScriptRoot "..\..")
    $sample = Resolve-NPDevSample -SamplesRoot $samplesRoot -SampleId "restaurant-saas-multitenant"
    $FinalExecRoot = $sample.AppRoot
}
$FinalExecRoot = Normalize-AbsolutePath $FinalExecRoot

if (-not (Test-Path -LiteralPath $FinalExecRoot -PathType Container)) {
    Fail ("FinalExecRoot not found: " + $FinalExecRoot)
}

$escapedTerms = $ForbiddenTerms | ForEach-Object { [regex]::Escape($_) }
$pattern = "(?i)(" + ($escapedTerms -join "|") + ")"
$violations = New-Object System.Collections.Generic.List[string]

Get-ChildItem -LiteralPath $FinalExecRoot -Recurse -File -Force | ForEach-Object {
    if (Is-AllowedPath -PathValue $_.FullName) {
        return
    }
    if (-not (Is-TextLikeFile -File $_)) {
        return
    }

    $content = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
    if ($null -eq $content) {
        return
    }

    $matches = [regex]::Matches($content, $pattern)
    if ($matches.Count -gt 0) {
        $terms = $matches | ForEach-Object { $_.Value } | Sort-Object -Unique
        $relativePath = Get-RelativePathCompat -Root $FinalExecRoot -PathValue $_.FullName
        $violations.Add(("{0}: {1}" -f $relativePath, ($terms -join ", ")))
    }
}

if ($violations.Count -gt 0) {
    Fail ("Domain-specific leakage found outside allowed sample/template/test folders:`n - " + ($violations -join "`n - "))
}

Write-Host "OK    No forbidden domain terms found outside allowed sample/template/test folders." -ForegroundColor Green
