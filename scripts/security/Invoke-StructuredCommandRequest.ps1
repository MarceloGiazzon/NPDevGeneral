param(
    [Parameter(Mandatory = $true)][string]$RequestPath,
    [string]$PolicyPath = "scripts/policy/ai-command-policy.json",
    [string]$ResultPath
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path ".").Path $PathValue))
}

function Test-IsUnderRoot {
    param([string]$PathValue, [string]$RootValue)
    $fullPath = [System.IO.Path]::GetFullPath($PathValue)
    $fullRoot = [System.IO.Path]::GetFullPath($RootValue)
    $comparison = [System.StringComparison]::OrdinalIgnoreCase
    if ($fullPath.Equals($fullRoot, $comparison)) {
        return $true
    }
    if (-not $fullRoot.EndsWith([System.IO.Path]::DirectorySeparatorChar)) {
        $fullRoot += [System.IO.Path]::DirectorySeparatorChar
    }
    return $fullPath.StartsWith($fullRoot, $comparison)
}

function Get-ExecutableName {
    param([string]$Value)
    $name = [System.IO.Path]::GetFileName($Value).ToLowerInvariant()
    if ($name.EndsWith(".exe")) {
        return [System.IO.Path]::GetFileNameWithoutExtension($name).ToLowerInvariant()
    }
    return $name
}

function Get-CommandErrorCode {
    param([AllowNull()][string]$Reason)
    if ([string]::IsNullOrWhiteSpace($Reason)) {
        return $null
    }
    switch ($Reason) {
        "REQUEST_TYPE_NOT_ALLOWED" { return "NPDEV_COMMAND_REQUEST_TYPE_NOT_ALLOWED" }
        "RAW_COMMANDS_DISABLED" { return "NPDEV_COMMAND_RAW_COMMAND_DENIED" }
        "EXECUTABLE_NOT_ALLOWED" { return "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED" }
        "EXECUTABLE_BLOCKED" { return "NPDEV_COMMAND_EXECUTABLE_NOT_ALLOWED" }
        "EXECUTABLE_OUTSIDE_WORKSPACE" { return "NPDEV_COMMAND_EXECUTABLE_OUTSIDE_SANDBOX" }
        "WORKING_DIRECTORY_OUTSIDE_WORKSPACE" { return "NPDEV_COMMAND_WORKDIR_OUTSIDE_SANDBOX" }
        "NETWORK_BLOCKED" { return "NPDEV_COMMAND_NETWORK_BLOCKED" }
        "ARGUMENT_BLOCKED" { return "NPDEV_COMMAND_ARGUMENT_DENIED" }
        "PWSH_COMMAND_MODE_BLOCKED" { return "NPDEV_COMMAND_ARGUMENT_DENIED" }
        "PWSH_FILE_OUTSIDE_ALLOWED_ROOT" { return "NPDEV_COMMAND_ARGUMENT_DENIED" }
        "REQUEST_SCHEMA_INVALID" { return "NPDEV_COMMAND_REQUEST_SCHEMA_INVALID" }
        default { return "NPDEV_COMMAND_DENIED" }
    }
}

function New-StructuredResult {
    param(
        [string]$Status,
        [AllowNull()][string]$BlockedReason,
        [AllowNull()][object]$ExitCode,
        [object]$CommandResult = $null
    )
    return [pscustomobject]@{
        schemaVersion = "npdev-structured-command-result.v1"
        status = $Status
        requestPath = $requestFullPath
        requestType = if ($null -ne $request) { [string]$request.type } else { "" }
        blockedReason = $BlockedReason
        errorCode = Get-CommandErrorCode $BlockedReason
        exitCode = $ExitCode
        commandResult = $CommandResult
    }
}

function Write-StructuredResult {
    param([object]$Result)
    if (-not [string]::IsNullOrWhiteSpace($ResultPath)) {
        $resultFullPath = Resolve-RepoPath $ResultPath
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resultFullPath) | Out-Null
        $Result | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $resultFullPath -Encoding UTF8
    }
    $Result | ConvertTo-Json -Depth 30
}

function Get-RawCommandBlockedReason {
    param([object]$Request, [object]$Policy)
    $executableName = Get-ExecutableName ([string]$Request.executable)
    $workspaceRoot = (Resolve-Path ".").Path
    if ([bool]$Policy.rawShellCommandsAllowed -ne $true) {
        if (@($Policy.blockedExecutables) -contains $executableName) { return "EXECUTABLE_BLOCKED" }
        if (@($Policy.allowedExecutables) -notcontains $executableName) { return "EXECUTABLE_NOT_ALLOWED" }
        $argumentText = (@($Request.arguments) -join " ")
        if ($argumentText -match "https?://(?!localhost(?::|/|$)|127\.0\.0\.1(?::|/|$)|\[::1\](?::|/|$))") { return "NETWORK_BLOCKED" }
        if ([System.IO.Path]::IsPathRooted([string]$Request.executable) -and -not (Test-IsUnderRoot ([System.IO.Path]::GetFullPath([string]$Request.executable)) $workspaceRoot)) { return "EXECUTABLE_OUTSIDE_WORKSPACE" }
        $workdir = Resolve-RepoPath ([string]$Request.workingDirectory)
        if (-not (Test-IsUnderRoot $workdir $workspaceRoot)) { return "WORKING_DIRECTORY_OUTSIDE_WORKSPACE" }
        foreach ($pattern in @($Policy.blockedArgumentPatterns)) {
            if ($argumentText -match [string]$pattern) { return "ARGUMENT_BLOCKED" }
        }
        if ($executableName -in @("pwsh", "powershell")) {
            $args = @($Request.arguments)
            if (@($args | Where-Object { $_ -in @("-Command", "-EncodedCommand", "/c") }).Count -gt 0) { return "PWSH_COMMAND_MODE_BLOCKED" }
        }
        return "RAW_COMMANDS_DISABLED"
    }
    return $null
}

function Get-RequestInt {
    param([object]$ObjectValue, [string]$PropertyName, [int]$DefaultValue)
    if ($null -eq $ObjectValue) { return $DefaultValue }
    $property = $ObjectValue.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) { return $DefaultValue }
    return [int]$property.Value
}

function Assert-RelativeSafePath {
    param([string]$PathValue, [string]$FieldName)
    if ([string]::IsNullOrWhiteSpace($PathValue) -or [System.IO.Path]::IsPathRooted($PathValue) -or $PathValue.Contains("..")) {
        throw "$FieldName must be a relative path without traversal."
    }
}

function Assert-UnderAllowedRoots {
    param([string]$CandidatePath, [string[]]$AllowedRoots, [string]$BlockedMessage)
    foreach ($root in @($AllowedRoots)) {
        $rootPath = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot ([string]$root)))
        if (Test-IsUnderRoot $CandidatePath $rootPath) {
            return
        }
    }
    throw $BlockedMessage
}

function Invoke-Controlled {
    param(
        [string]$Executable,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [int]$TimeoutSeconds,
        [string]$Name
    )
    $runnerResultPath = if (-not [string]::IsNullOrWhiteSpace($ResultPath)) { $ResultPath + "." + $Name + ".controlled-command.json" } else { $null }
    $argumentsJson = @($Arguments) | ConvertTo-Json -Compress
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/security/Invoke-ControlledCommand.ps1 `
        -Executable $Executable `
        -ArgumentsJson $argumentsJson `
        -WorkingDirectory $WorkingDirectory `
        -TimeoutSeconds $TimeoutSeconds `
        -ResultPath $runnerResultPath 2>$null | Out-Null
    $runnerExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    $runnerResult = if (-not [string]::IsNullOrWhiteSpace($runnerResultPath) -and (Test-Path -LiteralPath $runnerResultPath -PathType Leaf)) { Read-JsonFile $runnerResultPath } else { $null }
    return [pscustomobject]@{
        exitCode = $runnerExit
        result = $runnerResult
    }
}

function Get-GradleWrapperPath {
    param([string]$ProjectRoot)
    $windowsWrapper = Join-Path $ProjectRoot "gradlew.bat"
    $unixWrapper = Join-Path $ProjectRoot "gradlew"
    $preferredWrappers = if ($IsWindows -or $env:OS -eq "Windows_NT") {
        @($windowsWrapper, $unixWrapper)
    }
    else {
        @($unixWrapper, $windowsWrapper)
    }
    foreach ($candidate in $preferredWrappers) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    return $null
}

$workspaceRoot = (Resolve-Path ".").Path
$requestFullPath = Resolve-RepoPath $RequestPath
$policy = Read-JsonFile (Resolve-RepoPath $PolicyPath)
$request = $null

$schemaValidationPath = if (-not [string]::IsNullOrWhiteSpace($ResultPath)) { $ResultPath + ".schema-validation.json" } else { $null }
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath "schemas/ai/ai-command-request.schema.json" `
    -InstancePath $requestFullPath `
    -ReportPath $schemaValidationPath 2>$null | Out-Null
$schemaExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($schemaExit -ne 0) {
    $result = New-StructuredResult -Status "blocked" -BlockedReason "REQUEST_SCHEMA_INVALID" -ExitCode $null
    Write-StructuredResult $result | Out-Host
    exit 2
}

$request = Read-JsonFile $requestFullPath
$requestType = [string]$request.type
if (@($policy.allowedRequestTypes) -notcontains $requestType) {
    $reason = if ($requestType -eq "raw-command") { Get-RawCommandBlockedReason $request $policy } else { "REQUEST_TYPE_NOT_ALLOWED" }
    if ([string]::IsNullOrWhiteSpace($reason)) { $reason = "REQUEST_TYPE_NOT_ALLOWED" }
    $result = New-StructuredResult -Status "blocked" -BlockedReason $reason -ExitCode $null
    Write-StructuredResult $result | Out-Host
    exit 2
}

if ($requestType -eq "gradle-task") {
    try {
        Assert-RelativeSafePath ([string]$request.projectRoot) "projectRoot"
        $projectRoot = Resolve-RepoPath ([string]$request.projectRoot)
        Assert-UnderAllowedRoots $projectRoot @($policy.allowedGradleRoots) "Project root is not allowed for AI gradle-task execution."
        $task = [string]$request.task
        if ($task -notmatch "^[A-Za-z0-9:_-]+$" -or $task.StartsWith("-")) {
            throw "Gradle task must be a single safe task name."
        }
        $gradlew = Get-GradleWrapperPath $projectRoot
        if ([string]::IsNullOrWhiteSpace($gradlew)) {
            throw "Gradle wrapper is missing in projectRoot."
        }
        $run = Invoke-Controlled -Executable $gradlew -Arguments @($task, "--no-daemon", "--console=plain") -WorkingDirectory $projectRoot -TimeoutSeconds (Get-RequestInt $request "timeoutSeconds" ([int]$policy.defaultTimeoutSeconds)) -Name "gradle-task"
        $status = if ($run.exitCode -eq 0) { "passed" } elseif ($run.exitCode -eq 2) { "blocked" } else { "failed" }
        $result = New-StructuredResult -Status $status -BlockedReason $(if ($null -ne $run.result) { [string]$run.result.blockedReason } else { $null }) -ExitCode $run.exitCode -CommandResult $run.result
        Write-StructuredResult $result | Out-Host
        exit $run.exitCode
    }
    catch {
        $result = New-StructuredResult -Status "blocked" -BlockedReason "ARGUMENT_BLOCKED" -ExitCode $null -CommandResult ([pscustomobject]@{ error = $_.Exception.Message })
        Write-StructuredResult $result | Out-Host
        exit 2
    }
}

if ($requestType -eq "schema-validation") {
    try {
        Assert-RelativeSafePath ([string]$request.schemaPath) "schemaPath"
        Assert-RelativeSafePath ([string]$request.instancePath) "instancePath"
        $schemaPath = Resolve-RepoPath ([string]$request.schemaPath)
        $instancePath = Resolve-RepoPath ([string]$request.instancePath)
        Assert-UnderAllowedRoots $schemaPath @(".") "Schema path is outside the workspace."
        Assert-UnderAllowedRoots $instancePath @(".") "Instance path is outside the workspace."
        $validationReportPath = if (-not [string]::IsNullOrWhiteSpace($ResultPath)) { $ResultPath + ".schema-validation-result.json" } else { "scripts/reports/tmp/structured-command-schema-validation-result.json" }
        $run = Invoke-Controlled -Executable "pwsh" -Arguments @("-NoProfile", "-File", "scripts/quality/Invoke-JsonSchemaValidation.ps1", "-SchemaPath", [string]$request.schemaPath, "-InstancePath", [string]$request.instancePath, "-ReportPath", $validationReportPath) -WorkingDirectory "." -TimeoutSeconds (Get-RequestInt $request "timeoutSeconds" ([int]$policy.defaultTimeoutSeconds)) -Name "schema-validation"
        $status = if ($run.exitCode -eq 0) { "passed" } elseif ($run.exitCode -eq 2) { "blocked" } else { "failed" }
        $result = New-StructuredResult -Status $status -BlockedReason $(if ($null -ne $run.result) { [string]$run.result.blockedReason } else { $null }) -ExitCode $run.exitCode -CommandResult $run.result
        Write-StructuredResult $result | Out-Host
        exit $run.exitCode
    }
    catch {
        $result = New-StructuredResult -Status "blocked" -BlockedReason "ARGUMENT_BLOCKED" -ExitCode $null -CommandResult ([pscustomobject]@{ error = $_.Exception.Message })
        Write-StructuredResult $result | Out-Host
        exit 2
    }
}

if ($requestType -eq "rest-smoke") {
    try {
        Assert-RelativeSafePath ([string]$request.verificationPath) "verificationPath"
        $baseUrl = [string]$request.baseUrl
        $uri = [Uri]$baseUrl
        if ($uri.Scheme -notin @("http", "https") -or $uri.Host -notin @("localhost", "127.0.0.1", "::1")) {
            throw "rest-smoke baseUrl must be localhost."
        }
        $smokeReportPath = if (-not [string]::IsNullOrWhiteSpace($ResultPath)) { $ResultPath + ".rest-smoke-result.json" } else { "scripts/reports/tmp/structured-command-rest-smoke-result.json" }
        $arguments = @("-NoProfile", "-File", "scripts/ai/Invoke-AiRestSmokeVerifier.ps1", "-VerificationPath", [string]$request.verificationPath, "-BaseUrl", $baseUrl, "-ReportPath", $smokeReportPath)
        $expectedPort = Get-RequestInt $request "expectedPort" 0
        if ($expectedPort -gt 0) {
            $arguments += @("-ExpectedPort", [string]$expectedPort)
        }
        $run = Invoke-Controlled -Executable "pwsh" -Arguments $arguments -WorkingDirectory "." -TimeoutSeconds (Get-RequestInt $request "timeoutSeconds" ([int]$policy.defaultTimeoutSeconds)) -Name "rest-smoke"
        $status = if ($run.exitCode -eq 0) { "passed" } elseif ($run.exitCode -eq 2) { "blocked" } else { "failed" }
        $result = New-StructuredResult -Status $status -BlockedReason $(if ($null -ne $run.result) { [string]$run.result.blockedReason } else { $null }) -ExitCode $run.exitCode -CommandResult $run.result
        Write-StructuredResult $result | Out-Host
        exit $run.exitCode
    }
    catch {
        $result = New-StructuredResult -Status "blocked" -BlockedReason "ARGUMENT_BLOCKED" -ExitCode $null -CommandResult ([pscustomobject]@{ error = $_.Exception.Message })
        Write-StructuredResult $result | Out-Host
        exit 2
    }
}

$result = New-StructuredResult -Status "blocked" -BlockedReason "REQUEST_TYPE_NOT_ALLOWED" -ExitCode $null
Write-StructuredResult $result | Out-Host
exit 2
