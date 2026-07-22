$ErrorActionPreference = "Stop"

function New-AiShiftLeftFinding {
    param(
        [string]$Code,
        [string]$Path,
        [string]$Message,
        [string]$Stage = "pre-ast-safety-lint"
    )
    return [pscustomobject]@{
        code = $Code
        path = $Path
        message = $Message
        stage = $Stage
    }
}

function Test-NPDevUnsafePathText {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    return $Value -match "(^/|^[A-Za-z]:|\\\\|(^|[/\\])\.\.($|[/\\]))"
}

function Test-NPDevUnsafeExternalUrlText {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    return $Value -match "https?://(?!localhost(?::|/|$)|127\.0\.0\.1(?::|/|$)|\[::1\](?::|/|$))"
}

function Test-NPDevSuspiciousCommandText {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    return $Value -match '(?i)(Remove-Item|Invoke-WebRequest|Invoke-RestMethod|\birm\b|\biwr\b|\bcurl\b|\bwget\b|\bssh\b|\bscp\b|\brm\b|--delete|format-volume|;\s*|&&|\|\||`|\$\(|<\(|>\(|\b(pwsh|powershell|cmd|bash|sh)\b\s+(-Command|-EncodedCommand|/c|-c))'
}

function Add-NPDevStringSafetyFindings {
    param(
        [object]$Node,
        [string]$Path,
        [System.Collections.Generic.List[object]]$Findings
    )
    if ($null -eq $Node) {
        return
    }
    if ($Node -is [string]) {
        $value = [string]$Node
        $leaf = ($Path -split '[.\[]')[-1] -replace '\].*$',''
        $pathKey = $Path -replace '\[[0-9]+\]', ''
        $isPathLike = $leaf -match '(?i)(path|directory|entrypoint|url|uri|baseUrl|workingDirectory|projectRoot|schemaPath|instancePath|verificationPath)'
        $isCommandLike = $leaf -match '(?i)(command|script|executable|argument|arguments|externalCommand|value)' -or $pathKey -match '(?i)(\.arguments|\.command|\.script|\.executable|\.externalCommand)'
        if (($isPathLike -or $isCommandLike) -and (Test-NPDevUnsafeExternalUrlText $value)) {
            $Findings.Add((New-AiShiftLeftFinding "AI_SAFETY_UNSAFE_EXTERNAL_URL" $Path "External non-local URLs are not allowed in AI-generated safety-sensitive fields.")) | Out-Null
        }
        if ($isPathLike -and (Test-NPDevUnsafePathText $value)) {
            $Findings.Add((New-AiShiftLeftFinding "AI_SAFETY_PATH_TRAVERSAL" $Path "Absolute paths, UNC paths, and path traversal are not allowed in AI-generated safety-sensitive fields.")) | Out-Null
        }
        if ($isCommandLike -and (Test-NPDevSuspiciousCommandText $value)) {
            $Findings.Add((New-AiShiftLeftFinding "AI_SAFETY_SUSPICIOUS_COMMAND" $Path "Destructive shell commands, command chaining, command substitution, and direct shell command modes are not allowed.")) | Out-Null
        }
        return
    }
    if ($Node -is [System.Array]) {
        $index = 0
        foreach ($item in @($Node)) {
            Add-NPDevStringSafetyFindings -Node $item -Path ($Path + "[" + $index + "]") -Findings $Findings
            $index++
        }
        return
    }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $Node.PSObject.Properties) {
            Add-NPDevStringSafetyFindings -Node $property.Value -Path ($Path + "." + $property.Name) -Findings $Findings
        }
    }
}

function Invoke-AiShiftLeftSafetyLint {
    param(
        [object]$AiModel,
        [object]$AiConfig = $null,
        [object]$Verification = $null,
        [object]$CommandRequest = $null
    )
    $findings = [System.Collections.Generic.List[object]]::new()

    if ($null -ne $AiModel) {
        foreach ($procedure in @($AiModel.procedures)) {
            if ($null -eq $procedure) {
                continue
            }
            if ([string]$procedure.type -eq "bulk-command" -and [int]$procedure.maxAffectedRows -lt 1) {
                $findings.Add((New-AiShiftLeftFinding "PROCEDURE_BULK_LIMIT_MISSING" "$.procedures[?(@.procedureId=='$([string]$procedure.procedureId)')].maxAffectedRows" "Bulk procedures must declare maxAffectedRows greater than zero.")) | Out-Null
            }
            $implementation = if ($null -ne $procedure.PSObject) { $procedure.PSObject.Properties["implementation"] } else { $null }
            if ($null -ne $implementation -and $null -ne $implementation.Value) {
                $entrypoint = [string]$implementation.Value.entrypoint
                if (Test-NPDevUnsafePathText $entrypoint) {
                    $findings.Add((New-AiShiftLeftFinding "AI_SAFETY_PATH_TRAVERSAL" "$.procedures[?(@.procedureId=='$([string]$procedure.procedureId)')].implementation.entrypoint" "Trusted procedure entrypoints must be relative and traversal-free.")) | Out-Null
                }
            }
        }
        foreach ($panel in @($AiModel.panels)) {
            if ($null -eq $panel) {
                continue
            }
            $implementation = if ($null -ne $panel.PSObject) { $panel.PSObject.Properties["implementation"] } else { $null }
            if ($null -ne $implementation -and $null -ne $implementation.Value) {
                $entrypoint = [string]$implementation.Value.entrypoint
                if (Test-NPDevUnsafePathText $entrypoint) {
                    $findings.Add((New-AiShiftLeftFinding "AI_SAFETY_PATH_TRAVERSAL" "$.panels[?(@.panelId=='$([string]$panel.panelId)')].implementation.entrypoint" "Trusted panel entrypoints must be relative and traversal-free.")) | Out-Null
                }
            }
        }
        Add-NPDevStringSafetyFindings -Node $AiModel -Path "$" -Findings $findings
    }
    if ($null -ne $AiConfig) {
        if (Test-NPDevUnsafePathText ([string]$AiConfig.output.directory)) {
            $findings.Add((New-AiShiftLeftFinding "AI_SAFETY_PATH_TRAVERSAL" "$.output.directory" "Output directory must be relative and traversal-free.")) | Out-Null
        }
        Add-NPDevStringSafetyFindings -Node $AiConfig -Path "$" -Findings $findings
    }
    if ($null -ne $Verification) {
        Add-NPDevStringSafetyFindings -Node $Verification -Path "$" -Findings $findings
    }
    if ($null -ne $CommandRequest) {
        Add-NPDevStringSafetyFindings -Node $CommandRequest -Path "$" -Findings $findings
    }

    return @($findings)
}
