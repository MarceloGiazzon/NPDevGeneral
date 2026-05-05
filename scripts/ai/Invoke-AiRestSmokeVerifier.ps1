param(
    [Parameter(Mandatory = $true)][string]$VerificationPath,
    [string]$BaseUrl,
    [string]$ReportPath,
    [int]$ExpectedPort = 0,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

function Read-JsonFile {
    param([string]$Path)
    return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Add-Failure {
    param([System.Collections.Generic.List[string]]$Failures, [string]$Message)
    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        $Failures.Add($Message) | Out-Null
    }
}

function Test-LocalBaseUrl {
    param([string]$Url)
    $uri = [Uri]$Url
    if ($uri.Scheme -notin @("http", "https")) {
        return $false
    }
    return $uri.Host -in @("localhost", "127.0.0.1", "::1")
}

function ConvertTo-HashtableDeep {
    param([object]$Value)
    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $table = [ordered]@{}
        foreach ($property in $Value.PSObject.Properties) {
            $table[$property.Name] = ConvertTo-HashtableDeep $property.Value
        }
        return $table
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @()
        foreach ($item in $Value) {
            $items += ConvertTo-HashtableDeep $item
        }
        return $items
    }
    return $Value
}

function Convert-ResponseContentToString {
    param([object]$Content)
    if ($null -eq $Content) {
        return ""
    }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return [string]$Content
}

function Get-Sha256Text {
    param([string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    return ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::HashData($bytes)) -replace "-", "").ToLowerInvariant()
}

function Limit-Preview {
    param([string]$Text, [int]$MaxLength = 300)
    if ([string]::IsNullOrEmpty($Text)) { return "" }
    if ($Text.Length -le $MaxLength) { return $Text }
    return $Text.Substring(0, $MaxLength)
}

function Get-JsonProperty {
    param([object]$ObjectValue, [string]$PropertyName)
    if ($null -eq $ObjectValue) {
        return $null
    }
    if ($ObjectValue -is [System.Collections.IDictionary] -and $ObjectValue.Contains($PropertyName)) {
        return $ObjectValue[$PropertyName]
    }
    $property = $ObjectValue.PSObject.Properties[$PropertyName]
    if ($null -ne $property) {
        return $property.Value
    }
    return $null
}

function Assert-JsonContains {
    param(
        [object]$Actual,
        [object]$Expected,
        [string]$Path,
        [System.Collections.Generic.List[string]]$Failures
    )

    if ($Expected -is [System.Management.Automation.PSCustomObject] -or $Expected -is [System.Collections.IDictionary]) {
        foreach ($property in $Expected.PSObject.Properties) {
            $actualValue = Get-JsonProperty $Actual $property.Name
            if ($null -eq $actualValue) {
                Add-Failure $Failures ("Missing JSON property " + $Path + "." + $property.Name)
            }
            else {
                Assert-JsonContains $actualValue $property.Value ($Path + "." + $property.Name) $Failures
            }
        }
        return
    }

    if ($Expected -is [System.Collections.IEnumerable] -and $Expected -isnot [string]) {
        $actualJson = ConvertTo-Json $Actual -Depth 20 -Compress
        $expectedJson = ConvertTo-Json $Expected -Depth 20 -Compress
        if ($actualJson -ne $expectedJson) {
            Add-Failure $Failures ("JSON mismatch at " + $Path + ": expected " + $expectedJson + " but got " + $actualJson)
        }
        return
    }

    if ([string]$Actual -ne [string]$Expected) {
        Add-Failure $Failures ("JSON mismatch at " + $Path + ": expected " + [string]$Expected + " but got " + [string]$Actual)
    }
}

function Join-BaseAndPath {
    param([string]$Url, [string]$PathValue)
    $base = $Url.TrimEnd("/")
    return $base + $PathValue
}

$planValidationPath = if (-not [string]::IsNullOrWhiteSpace($ReportPath)) { $ReportPath + ".plan-validation.json" } else { $null }
$ErrorActionPreference = "Continue"
pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 `
    -SchemaPath "schemas/ai/ai-verification-report.schema.json" `
    -JsonPath $VerificationPath `
    -ReportPath $planValidationPath 2>$null | Out-Null
$planValidationExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($planValidationExit -ne 0) {
    throw "Verification plan failed strict schema validation."
}

$verification = Read-JsonFile $VerificationPath

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $variableName = [string]$verification.baseUrlVariable
    $BaseUrl = [Environment]::GetEnvironmentVariable($variableName)
}
if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    throw "BaseUrl was not provided and the configured environment variable is empty."
}
if (-not (Test-LocalBaseUrl $BaseUrl)) {
    throw "AI REST smoke verifier only accepts localhost base URLs."
}
$baseUri = [Uri]$BaseUrl
if ($ExpectedPort -gt 0 -and $baseUri.Port -ne $ExpectedPort) {
    throw ("AI REST smoke verifier base URL port must match generated app port " + [string]$ExpectedPort + ".")
}

$checkResults = @()
$overallStatus = "passed"

foreach ($check in @($verification.checks)) {
    $failures = [System.Collections.Generic.List[string]]::new()
    $actualStatus = $null
    $responseText = ""
    $responseJson = $null
    $durationMs = 0
    $assertions = [System.Collections.Generic.List[object]]::new()

    if (-not ([string]$check.path).StartsWith("/") -or [string]$check.path -match "^https?://") {
        Add-Failure $failures "Check path must be a local absolute path, not a full URL."
    }

    if ($failures.Count -eq 0) {
        $uri = Join-BaseAndPath $BaseUrl ([string]$check.path)
        $headers = @{}
        if ($null -ne $check.headers) {
            foreach ($property in $check.headers.PSObject.Properties) {
                $headers[$property.Name] = [string]$property.Value
            }
        }
        $body = $null
        $contentType = $null
        if ($null -ne $check.body) {
            $body = ConvertTo-Json (ConvertTo-HashtableDeep $check.body) -Depth 20 -Compress
            $contentType = if ($headers.ContainsKey("Content-Type")) { [string]$headers["Content-Type"] } else { "application/json" }
        }

        try {
            $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-WebRequest -Uri $uri -Method ([string]$check.method) -Headers $headers -Body $body -ContentType $contentType -TimeoutSec $TimeoutSeconds -SkipHttpErrorCheck
            $stopwatch.Stop()
            $durationMs = [int]$stopwatch.ElapsedMilliseconds
            $actualStatus = [int]$response.StatusCode
            $responseText = Convert-ResponseContentToString $response.Content
            if (-not [string]::IsNullOrWhiteSpace($responseText)) {
                try {
                    $responseJson = $responseText | ConvertFrom-Json
                }
                catch {
                    $responseJson = $null
                }
            }
        }
        catch {
            Add-Failure $failures ("HTTP request failed: " + $_.Exception.Message)
        }
    }

    if ($null -ne $actualStatus -and $actualStatus -ne [int]$check.expectedStatus) {
        Add-Failure $failures ("Expected HTTP status " + [string]$check.expectedStatus + " but got " + [string]$actualStatus + ".")
    }
    $assertions.Add([pscustomobject]@{
        type = "status"
        expected = [int]$check.expectedStatus
        actual = $actualStatus
        passed = ($null -ne $actualStatus -and $actualStatus -eq [int]$check.expectedStatus)
    }) | Out-Null

    if ($null -ne $check.expectedJson) {
        if ($null -eq $responseJson) {
            Add-Failure $failures "Expected JSON response but response was empty or not JSON."
        }
        else {
            $actualJson = ConvertTo-Json $responseJson -Depth 20 -Compress
            $expectedJson = ConvertTo-Json $check.expectedJson -Depth 20 -Compress
            if ($actualJson -ne $expectedJson) {
                Add-Failure $failures ("Expected JSON " + $expectedJson + " but got " + $actualJson + ".")
            }
            $assertions.Add([pscustomobject]@{
                type = "expectedJson"
                expectedHash = Get-Sha256Text $expectedJson
                actualHash = Get-Sha256Text $actualJson
                passed = ($actualJson -eq $expectedJson)
            }) | Out-Null
        }
    }

    if ($null -ne $check.expectedJsonContains) {
        if ($null -eq $responseJson) {
            Add-Failure $failures "Expected JSON contains assertion but response was empty or not JSON."
        }
        else {
            $beforeCount = $failures.Count
            Assert-JsonContains $responseJson $check.expectedJsonContains '$' $failures
            $expectedContainsJson = ConvertTo-Json $check.expectedJsonContains -Depth 20 -Compress
            $assertions.Add([pscustomobject]@{
                type = "expectedJsonContains"
                expectedHash = Get-Sha256Text $expectedContainsJson
                passed = ($failures.Count -eq $beforeCount)
            }) | Out-Null
        }
    }

    $status = if ($failures.Count -eq 0) { "passed" } else { "failed" }
    if ($status -eq "failed") {
        $overallStatus = "failed"
    }
    $checkResults += [pscustomobject]@{
        id = [string]$check.id
        status = $status
        method = [string]$check.method
        path = [string]$check.path
        expectedStatus = [int]$check.expectedStatus
        actualStatus = $actualStatus
        durationMs = $durationMs
        responseBodySha256 = Get-Sha256Text $responseText
        responseBodyPreview = Limit-Preview $responseText
        assertions = @($assertions)
        failures = @($failures)
    }
}

$report = [pscustomobject]@{
    schemaVersion = "npdev-ai-rest-smoke-result.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/ai/Invoke-AiRestSmokeVerifier.ps1"
    status = $overallStatus
    scenarioId = [string]$verification.scenarioId
    baseUrl = $BaseUrl
    expectedPort = if ($ExpectedPort -gt 0) { $ExpectedPort } else { $null }
    planValidation = $planValidationPath
    checks = $checkResults
}

if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    $reportDirectory = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
        New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    }
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}

$report | ConvertTo-Json -Depth 20
if ($overallStatus -eq "passed") {
    exit 0
}
exit 1
