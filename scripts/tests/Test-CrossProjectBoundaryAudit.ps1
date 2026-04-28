Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

function Write-JsonFileForTest {
    param(
        [string]$PathValue,
        [object]$Value
    )

    $parent = Split-Path -Parent $PathValue
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $PathValue -Encoding UTF8
}

function New-CrossProjectBoundaryFixture {
    param(
        [string]$RootPath
    )

    if (Test-Path -LiteralPath $RootPath) {
        Remove-Item -LiteralPath $RootPath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $RootPath | Out-Null

    $scriptsRoot = Join-Path $RootPath "scripts"
    New-Item -ItemType Directory -Force -Path (Join-Path $scriptsRoot "quality") | Out-Null
    Copy-Item -LiteralPath (Resolve-NPDevWorkspacePath (Get-NPDevWorkspaceRoot $PSScriptRoot) "scripts\quality\run-cross-project-boundary-audit.ps1") -Destination (Join-Path $scriptsRoot "quality\run-cross-project-boundary-audit.ps1") -Force
    Copy-Item -LiteralPath (Resolve-NPDevWorkspacePath (Get-NPDevWorkspaceRoot $PSScriptRoot) "scripts\npdev-common.ps1") -Destination (Join-Path $scriptsRoot "npdev-common.ps1") -Force

    $outRoot = Join-Path $RootPath "scripts\reports\out"
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "domain-leak-report.json") -Value @{
        overallStatus = "passed"
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "root-build-coupling-report.json") -Value @{
        overallStatus = "passed"
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "contract-surface-consistency-report.json") -Value @{
        overallStatus = "passed"
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "entity-canonical-surface-report.json") -Value @{
        overallStatus = "passed"
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "contract-gate-report.json") -Value @{
        workingDirectory = "NPDevContract\dsl"
        command = @{
            executable = "D:\Fixture\NPDevContract\dsl\gradlew.bat"
        }
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "editor-gate-report.json") -Value @{
        workingDirectory = "NPDevEditor"
        command = @{
            executable = "D:\Fixture\NPDevEditor\gradlew.bat"
        }
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "frontend-gate-report.json") -Value @{
        subSteps = @(
            @{
                name = "dependency-install"
                workingDirectory = "NPDevEditor"
                command = @{
                    executable = "NPDevEditor\gradlew.bat"
                }
            },
            @{
                name = "test"
                workingDirectory = "NPDevEditor"
                command = @{
                    executable = "NPDevEditor\gradlew.bat"
                }
            },
            @{
                name = "build"
                workingDirectory = "NPDevEditor"
                command = @{
                    executable = "NPDevEditor\gradlew.bat"
                }
            }
        )
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "generator-gate-report.json") -Value @{
        workingDirectory = "NPDevGenerator"
        command = @{
            executable = "D:\Fixture\NPDevGenerator\gradlew.bat"
        }
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "kernel-gate-report.json") -Value @{
        workingDirectory = "NPDevKernel"
        command = @{
            executable = "D:\Fixture\NPDevKernel\gradlew.bat"
        }
    }
    Write-JsonFileForTest -PathValue (Join-Path $outRoot "runtimehost-gate-report.json") -Value @{
        assembledAppRoot = "D:\Fixture\NPDevSamples\simple-contact-intake\Output\App"
        verificationCommand = @{
            workingDirectory = "NPDevSamples\simple-contact-intake\Output\App"
            executable = ".\gradlew.bat"
        }
    }

    return [pscustomobject]@{
        root = (Normalize-NPDevPath $RootPath)
        scriptPath = (Join-Path $scriptsRoot "quality\run-cross-project-boundary-audit.ps1")
    }
}

$workspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
$fixtureRoot = Join-Path $env:TEMP "npdev-cross-project-boundary-audit"
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        [void]$failures.Add($Message)
    }
}

try {
    $fixture = New-CrossProjectBoundaryFixture -RootPath $fixtureRoot
    $report = & $fixture.scriptPath -WorkspaceRoot $fixture.root -PassThru

    Assert-True ([string]$report.overallStatus -eq "passed") ("Expected the cross-project boundary audit fixture to pass. Actual: " + [string]$report.overallStatus)
    Assert-True (@($report.gateAudits | Where-Object { [string]$_.name -eq "frontend-gate" -and [bool]$_.passed }).Count -eq 1) "Expected the current frontend gate evidence shape to pass the audit."
    Assert-True (@($report.gateAudits | Where-Object { [string]$_.name -eq "runtimehost-gate" -and [bool]$_.passed }).Count -eq 1) "Expected the current runtimehost gate evidence shape to pass the audit."
}
catch {
    [void]$failures.Add($_.Exception.Message)
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}

if ($failures.Count -eq 0) {
    Write-NPDevOk "Cross-project boundary audit tests passed."
    exit 0
}

foreach ($failure in $failures) {
    Write-NPDevWarn $failure
}
throw "Cross-project boundary audit tests failed."
