param(
    [string]$WorkspaceRoot = ".",
    [string]$ReportPath = "scripts/reports/out/portable-tooling-report.json",
    [string]$SchemaPath = "schemas/ai/portable-tooling-report.schema.json",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"

function Convert-ToRepoPath {
    param([string]$Root, [string]$PathValue)
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathValue)
    if ($resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return ($resolvedPath.Substring($resolvedRoot.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return ($resolvedPath -replace "\\", "/")
}

# md-zero-2026-08-11 PLAN.md Phase 5: reconstructs a Group D doc's rendered text from its
# content/*.json mirror -- the same data scripts/docs/generate_group_d_docs.py renders into the
# actual .md file, so this stays byte-identical to what a reader sees without this script ever
# opening README.md / docs/GETTING_STARTED.md itself.
function Get-RenderedContentDoc {
    param([string]$Root, [string]$ContentJsonRelativePath)
    $jsonPath = Join-Path $Root $ContentJsonRelativePath
    $doc = Get-Content -Raw -LiteralPath $jsonPath | ConvertFrom-Json
    $lines = [System.Collections.Generic.List[string]]::new()
    $renderBlocks = {
        param($blocks)
        foreach ($block in $blocks) {
            $text = [string]$block.text
            $blockLines = if ($text -eq "") { @("") } else { $text -split "`n" }
            if ($block.type -eq "prose") {
                foreach ($l in $blockLines) { $lines.Add($l) }
            }
            else {
                $lines.Add('```' + [string]$block.lang)
                foreach ($l in $blockLines) { $lines.Add($l) }
                $lines.Add('```')
            }
        }
    }
    & $renderBlocks $doc.preamble
    foreach ($section in $doc.sections) {
        $lines.Add(("#" * [int]$section.level) + " " + [string]$section.title)
        & $renderBlocks $section.blocks
    }
    return ($lines -join "`n")
}

function Get-BashPath {
    $command = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    foreach ($candidate in @(
            "C:\Program Files\Git\bin\bash.exe",
            "C:\Program Files\Git\usr\bin\bash.exe"
        )) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return ""
}

function Invoke-NpdevCommand {
    param(
        [string]$Name,
        [string]$CommandLine,
        [string]$Root,
        [int]$ExpectedExitCode = 0
    )
    $bashPath = Get-BashPath
    $started = Get-Date
    Push-Location $Root
    try {
        if (-not [string]::IsNullOrWhiteSpace($bashPath)) {
            $output = @(& $bashPath -lc $CommandLine 2>&1 | ForEach-Object { $_.ToString() })
            $exitCode = $LASTEXITCODE
            $actualCommand = "bash -lc `"$CommandLine`""
        }
        else {
            throw "bash is required to verify the POSIX ./npdev wrapper"
        }
    }
    finally {
        Pop-Location
    }
    $finished = Get-Date
    return [pscustomobject]@{
        name = $Name
        command = $actualCommand
        exitCode = $exitCode
        expectedExitCode = $ExpectedExitCode
        passed = ($exitCode -eq $ExpectedExitCode)
        durationSeconds = [math]::Round(($finished - $started).TotalSeconds, 3)
        outputTail = @($output | Select-Object -Last 40)
    }
}

function Get-PathNeutralityScanScope {
    return @(
        "README.md",
        "docs/GETTING_STARTED.md",
        ".github/workflows/npdev-ci-validation.yml",
        "NPDevContract/examples/valid/simple-config.json",
        "npdev",
        "npdev.bat",
        "NPDevCli/**/*.py",
        "NPDevCli/**/*.md",
        "NPDevCli/**/*.json",
        "NPDevCli/**/*.txt",
        "NPDevCli/**/*.bat",
        "NPDevMcp/**/*.py",
        "NPDevMcp/**/*.md",
        "NPDevMcp/**/*.json",
        "NPDevMcp/**/*.txt",
        "NPDevMcp/**/*.bat",
        "scripts/**/*.py",
        "scripts/**/*.md",
        "scripts/**/*.json",
        "scripts/**/*.txt",
        "scripts/**/*.bat"
    )
}

function Get-PathNeutralityExcludedPaths {
    return @(
        [pscustomobject]@{ path = "scripts/reports/out/**"; reason = "Generated report output may contain runtime absolute paths and is not source config." },
        [pscustomobject]@{ path = "scripts/reports/tmp/**"; reason = "Temporary validation output may contain runtime absolute paths." },
        [pscustomobject]@{ path = "build/**"; reason = "Generated build output may contain runtime absolute paths." },
        [pscustomobject]@{ path = "docs/maintainers/ROADMAP_BOUNDARY_POLICY.md"; reason = "Accepted CP0 evidence policy intentionally records local checkpoint path options." },
        [pscustomobject]@{ path = "docs/maintainers/POST_BETA0_HUMAN_ACTION_REGISTER.md"; reason = "Accepted CP0 human-action evidence records prior local bundle paths." },
        [pscustomobject]@{ path = "docs/maintainers/OFFICIAL_BETA_RELEASE_RUNBOOK.md"; reason = "Historical Beta0 release runbook is outside CP5 portable quick-start scope." },
        [pscustomobject]@{ path = "docs/archive/programme-history/RELEASE_BLOCKER_EXECUTION_ROADMAP.md"; reason = "Historical release-blocker evidence path is outside CP5 portable quick-start scope." },
        [pscustomobject]@{ path = "**/MIGRATION_DIGEST.md"; reason = "Historical migration digests preserve source-local path provenance." },
        [pscustomobject]@{ path = "scripts/quality/run-controlled-command-runner-tests.ps1"; reason = "Intentional security-test fixture uses drive-letter examples." },
        [pscustomobject]@{ path = "NPDevCli/tests/test_pack_signing.py"; reason = "GitCoordinateParseUnitTest.test_without_subpath constructs a synthetic git+file:///D:/x/y@v2.0.0 coordinate specifically to test Windows drive-letter parsing -- the string IS the test." },
        # 2026-08-23 (T2.1): the entries below are the triage of the first run that ever scanned
        # NPDevMcp/ and scripts/. Every one is a hit that CANNOT reach CI: prose, recorded run
        # output, a synthetic test fixture, or a manual-runbook script CI never invokes. There is
        # deliberately NO blanket "scripts/*" entry -- that would restore the blind spot the
        # widened scan just closed. Anything gate-reachable (script-invocation-declarations.json
        # says "ci-gate") is fixed at the source instead, never excluded here.
        [pscustomobject]@{
            path   = "scripts/policy/scale-proof-baseline.json"
            reason = "Recorded run OUTPUT, not source config: run-scale-proof.ps1 appends each nightly rung's measurement messages, which quote the out-of-repo Input/Output paths of the machine that ran it. Same class as the scripts/reports/out/** entry above. Nothing reads these strings as a path (schemas/ai/scale-proof-report.schema.json treats them as free-text messages) and nightly-scale-ladder.yml deliberately does not commit CI's copy back, so no CI job can ever consume this machine's values."
        },
        [pscustomobject]@{
            path   = "scripts/policy/maturity-max-roadmap-policy.json"
            reason = "Historical CP0 evidence provenance (authoritativeRoadmapInput, evidencePathPolicy.cursorLocalDefault, humanActionRegister evidencePath) recording where a past roadmap input and its checkpoint bundles physically lived. scripts/docs/generate_maturity_max_roadmap_docs.py only RENDERS these strings into docs/maintainers/ROADMAP_BOUNDARY_POLICY.md and POST_BETA0_HUMAN_ACTION_REGISTER.md -- both of which are already excluded above for this exact reason -- and never opens them as paths, so nothing on CI resolves them."
        },
        [pscustomobject]@{
            path   = "scripts/hygiene/out-of-tree-generation-baseline.json"
            reason = "The only match is inside a `reason` string that itself explains why a hardcoded D:/WorkSpace build-output path must not be used. Prose about the defect is not the defect (the same rule the Python prose stripper applies to comments); the field is displayed by the hygiene report, never resolved."
        },
        [pscustomobject]@{
            path   = "scripts/external-review/missions.json"
            reason = "The only match is inside mission M1's human-readable `description`, explaining that emitted Java lives per-generated-app under the out-of-repo Build root and therefore has NO static in-repo path configured. It is documentation of an absent path, not a path: the schema-validated `paths` array it describes is empty."
        },
        [pscustomobject]@{
            path   = "scripts/external-review/build-review-pack.py"
            reason = "Operator convenience: DEFAULT_OUT_ROOT points at this developer machine's __OutsideRepo/external-ai-review/packs because ADR-0009 requires review packs to be written OUTSIDE the repo, and --out-root overrides it. Declared `invocation: manual-runbook` in scripts/policy/script-invocation-declarations.json -- no run-*.ps1 gate and no .github workflow invokes it, so CI never evaluates the default."
        },
        [pscustomobject]@{
            path   = "scripts/quality/check-workflow-yaml-syntax.py"
            reason = 'False positive, not a path: the matches are the two-character escape sequence in the inline YAML calibration fixture ''jobs:\n  x:\n    steps:\n'' -- the regex reads the single-letter YAML key x plus :\ as a drive. The file holds no filesystem default at all (it walks .github/workflows relative to __file__).'
        },
        [pscustomobject]@{
            path   = "scripts/quality/check-ci-adapter-coverage.py"
            reason = 'False positive, same shape as check-workflow-yaml-syntax.py above: the match is the x:\n escape inside the synthetic workflow text built by its --calibrate control. The file holds no filesystem default (settings.gradle and .github/workflows are resolved relative to __file__).'
        },
        [pscustomobject]@{
            path   = "scripts/quality/firstrun-harness/README.md"
            reason = 'Prose: one sentence listing the local preconditions a warm developer machine happens to satisfy (a Gradle cache, and this machine''s own Build root "present") to contrast them with the cold container the harness actually builds. The harness itself takes its source root from the LOCAL_SRC docker mount, so no CI path is derived from this line.'
        },
        [pscustomobject]@{
            path   = "NPDevMcp/README.md"
            reason = "Prose: a worked example of the MCP client's own claude_desktop_config.json, which requires an absolute path by protocol -- the reader must substitute their own checkout either way. Nothing executes or parses this README; the server resolves its root from the NPDEV_ROOT env var shown in the same snippet."
        }
    )
}

function Get-ScopedPathNeutralityFiles {
    param([string]$Root)
    $explicit = @(
        "README.md",
        "docs/GETTING_STARTED.md",
        ".github/workflows/npdev-ci-validation.yml",
        "NPDevContract/examples/valid/simple-config.json",
        "npdev",
        "npdev.bat"
    )
    foreach ($path in $explicit) {
        $full = Join-Path $Root $path
        if (Test-Path -LiteralPath $full -PathType Leaf) { Get-Item -LiteralPath $full }
    }
    # 2026-08-23: this scan covered six named files plus NPDevCli only, while its report line reads
    # as repo-wide. scripts/ was invisible, which is how check-pack-coverage.py shipped with
    # `Path(r"D:\WorkSpace\NPDev\AppGen\apps")` as a DEFAULT and turned the AI knowledge gate red on
    # every pull request. These are the trees whose contents run in a gate, on CI, or on a
    # contributor's machine -- the places REG-144's rule actually has to hold.
    foreach ($dir in @("NPDevCli", "NPDevMcp", "scripts")) {
        $fullDir = Join-Path $Root $dir
        if (Test-Path -LiteralPath $fullDir -PathType Container) {
            Get-ChildItem -LiteralPath $fullDir -Recurse -File | Where-Object {
                $_.Extension -in @(".py", ".md", ".json", ".txt", ".bat")
            }
        }
    }
}

# Python COMMENTS AND DOCSTRINGS are prose, and prose about a drive-letter defect is not a
# drive-letter defect. PORT-2/QUAL-3's records explain the bug in exactly those words -- "`npdev init
# D:\Apps\my-app` generates into `D:\Apps\my-app-app`" -- and six such occurrences in four lines of
# NPDevCli/npdev_cli.py and NPDevCli/tests/test_ops_toolbox_isolation.py turned this check red in CI
# run 31421541918 (2026-08-10), the first run in months where it actually executed.
#
# strip_python_prose.py removes comments and docstrings ONLY. Every other string literal stays in
# scope, so a real `default = "D:\WorkSpace\Build"` still fails -- which is the point, since
# NPDevCli is exactly where REG-144's "never hardcode a drive letter as a default" would be broken.
# Excluding the two whole files was the cheap alternative and was rejected for blinding the scan in
# the file most likely to acquire one. (Get-PathNeutralityExcludedPaths was REPORTED but never
# applied as a filter until 23a3403f; it is a real filter now -- see Get-HardcodedDriveMatches.)
$script:PythonProseStrippedFileCount = 0
function Get-PythonScannableText {
    param([string]$Root, [string[]]$Paths)
    $map = @{}
    if (-not $Paths -or @($Paths).Count -eq 0) { return $map }
    $stripper = Join-Path $Root "scripts/quality/strip_python_prose.py"
    if (-not (Test-Path -LiteralPath $stripper -PathType Leaf)) { return $map }
    $exe = $null
    foreach ($candidate in @("python", "python3")) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) { $exe = $candidate; break }
    }
    if (-not $exe) { return $map }
    try {
        $raw = & $exe $stripper @Paths 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $raw) { return @{} }
        foreach ($entry in ((@($raw) -join "") | ConvertFrom-Json)) { $map[$entry.path] = $entry.text }
    } catch {
        # FAIL OPEN, NEVER BLIND: an empty map falls back to raw text below, which can only produce a
        # false positive a maintainer investigates -- never a silent green.
        return @{}
    }
    return $map
}

function Get-HardcodedDriveMatches {
    param([string]$Root)
    # Not "$matches" -- that name collides case-insensitively with PowerShell's automatic
    # $Matches variable (set by any -match/-notmatch, including ones in a nested scriptblock),
    # which silently replaces the accumulator with a regex-capture Hashtable and later breaks
    # ConvertTo-Json ("System.Collections.Hashtable is not supported"). See Get-GradlePwshCoreTaskMatches.
    #
    # Get-PathNeutralityExcludedPaths WAS computed and reported elsewhere but never actually applied
    # as a filter here -- confirmed live: NPDevCli/tests/test_pack_signing.py's
    # GitCoordinateParseUnitTest.test_without_subpath legitimately constructs a synthetic
    # "git+file:///D:/x/y@v2.0.0" coordinate to test Windows drive-letter parsing, which is exactly
    # the "intentional test fixture" class the existing run-controlled-command-runner-tests.ps1
    # exclusion entry already exists to cover -- it just never took effect for any entry.
    $excludedPaths = @(Get-PathNeutralityExcludedPaths)
    $found = @()
    $scopedFiles = @(Get-ScopedPathNeutralityFiles -Root $Root) | Where-Object {
        $candidate = Convert-ToRepoPath -Root $Root -PathValue $_.FullName
        -not @($excludedPaths | Where-Object { $candidate -like $_.path }).Count
    }
    $pythonPaths = @($scopedFiles | Where-Object { $_.Extension -eq ".py" } | ForEach-Object { $_.FullName })
    $scannable = Get-PythonScannableText -Root $Root -Paths $pythonPaths
    $script:PythonProseStrippedFileCount = @($scannable.Keys).Count
    # md-zero-2026-08-11 PLAN.md Phase 5: README.md and docs/GETTING_STARTED.md are GENERATED from
    # content/*.json (scripts/docs/generate_group_d_docs.py) -- read the rendered text back from
    # that JSON mirror instead of the .md file itself, byte-identical either way.
    $groupDContentSource = @{
        "README.md"               = "content/readme.json"
        "docs/GETTING_STARTED.md" = "content/getting-started.json"
    }
    foreach ($file in $scopedFiles) {
        $repoRelative = Convert-ToRepoPath -Root $Root -PathValue $file.FullName
        $text = if ($scannable.ContainsKey($file.FullName)) {
            $scannable[$file.FullName]
        }
        elseif ($groupDContentSource.ContainsKey($repoRelative)) {
            Get-RenderedContentDoc -Root $Root -ContentJsonRelativePath $groupDContentSource[$repoRelative]
        }
        else {
            Get-Content -Raw -LiteralPath $file.FullName
        }
        $regexMatches = [regex]::Matches($text, "(?<![A-Za-z])[A-Za-z]:[\\/]")
        foreach ($match in $regexMatches) {
            $found += [pscustomobject]@{
                path = Convert-ToRepoPath -Root $Root -PathValue $file.FullName
                value = $match.Value
            }
        }
    }
    return @($found)
}

function Get-GradlePwshCoreTaskMatches {
    param([string]$Root)
    # Same $matches/$Matches collision as Get-HardcodedDriveMatches above -- this function is the
    # one that actually hits it: the Where-Object filter's -notmatch below runs against every
    # gradle file under a populated build/ output dir, which sets the automatic $Matches variable
    # and clobbers a same-named accumulator before the pwsh/powershell scan below ever runs.
    $found = @()
    $gradleFiles = Get-ChildItem -LiteralPath $Root -Recurse -File -Include "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" |
        Where-Object { $_.FullName -notmatch "\\build\\" -and $_.FullName -notmatch "\\\.gradle\\" }
    foreach ($file in $gradleFiles) {
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $file.FullName) {
            $lineNumber++
            if ($line -match "\bpwsh\b.*\s-File\b" -or $line -match "\bpowershell\b.*\s-File\b") {
                $found += [pscustomobject]@{
                    path = Convert-ToRepoPath -Root $Root -PathValue $file.FullName
                    line = $lineNumber
                    text = $line.Trim()
                }
            }
        }
    }
    return @($found)
}

$workspaceRootPath = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "portable-tooling-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

Push-Location $workspaceRootPath
try {
    $commands = @(
        (Invoke-NpdevCommand -Name "npdev-version" -Root $workspaceRootPath -CommandLine "./npdev --version")
        (Invoke-NpdevCommand -Name "npdev-validate-model" -Root $workspaceRootPath -CommandLine "./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json")
        # F1: `validate model` now runs full semantic validation by default (npdev_cli.py's
        # run_validate_semantic), which reports a failed model via exit 2, not the schema-only
        # path's exit 1 -- this fixture is still rejected, just by the stronger default validator.
        (Invoke-NpdevCommand -Name "npdev-invalid-model-rejected" -Root $workspaceRootPath -CommandLine "./npdev validate model NPDevCli/tests/fixtures/invalid-model.json" -ExpectedExitCode 2)
        (Invoke-NpdevCommand -Name "npdev-normalize-ai-model" -Root $workspaceRootPath -CommandLine "mkdir -p build && ./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json > build/npdev-normalized-model.json")
        (Invoke-NpdevCommand -Name "npdev-generate-app" -Root $workspaceRootPath -CommandLine "./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output build/npdev-generated")
    )

    $hardcodedMatches = Get-HardcodedDriveMatches -Root $workspaceRootPath
    $gradleMatches = Get-GradlePwshCoreTaskMatches -Root $workspaceRootPath
    $pathNeutralityScanScope = Get-PathNeutralityScanScope
    $pathNeutralityExcludedPaths = Get-PathNeutralityExcludedPaths
    $readmeText = Get-RenderedContentDoc -Root $workspaceRootPath -ContentJsonRelativePath "content/readme.json"
    $gettingStartedText = Get-RenderedContentDoc -Root $workspaceRootPath -ContentJsonRelativePath "content/getting-started.json"
    $linuxExamplesPresent = (
        $readmeText.Contains("./npdev validate model") -and
        $readmeText.Contains("./npdev normalize ai-model") -and
        $readmeText.Contains("./npdev generate app") -and
        $gettingStartedText.Contains("Linux and macOS") -and
        $gettingStartedText.Contains("./npdev report bootstrap")
    )

    $cliToolExists = (Test-Path -LiteralPath (Join-Path $workspaceRootPath "npdev") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $workspaceRootPath "npdev.bat") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py") -PathType Leaf)

    $findings = @(
        [pscustomobject]@{
            id = "CP5-POWERSHELL-COMPATIBILITY-WRAPPERS-RETAINED"
            classification = "known-risk-accepted"
            status = "accepted"
            description = "PowerShell scripts remain available as compatibility wrappers; CP5 adds portable core entrypoints rather than deleting historical scripts."
        }
    )

    if (@($hardcodedMatches).Count -gt 0) {
        $findings += [pscustomobject]@{
            id = "CP5-HARDCODED-DRIVE-LETTERS-IN-SCOPE"
            classification = "current-checkpoint-blocker"
            status = "open"
            description = "Hardcoded drive-letter paths remain in the CP5 path-neutrality scan scope."
        }
    }
    if (@($gradleMatches).Count -gt 0) {
        $findings += [pscustomobject]@{
            id = "CP5-GRADLE-CORE-TASK-PWSH-FILE"
            classification = "current-checkpoint-blocker"
            status = "open"
            description = "A Gradle core validation task invokes pwsh or powershell with -File."
        }
    }

    $report = [pscustomobject]@{
        schemaVersion = "npdev-portable-tooling-report.v1"
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        scriptPath = "scripts/quality/run-portable-tooling-check.ps1"
        workspaceRoot = $workspaceRootPath
        overallStatus = "failed"
        cliToolExists = $cliToolExists
        cliVersionWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-version" -and $_.passed }).Count -eq 1)
        cliModelValidationWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-validate-model" -and $_.passed }).Count -eq 1)
        cliModelValidationUsesCanonicalSchema = [bool]((Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("NPDevContract") -and (Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("model.schema.json") -and (Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("validate-json-schema.mjs"))
        cliInvalidModelRejected = [bool](@($commands | Where-Object { $_.name -eq "npdev-invalid-model-rejected" -and $_.passed -and $_.exitCode -eq 2 }).Count -eq 1)
        cliAiNormalizeWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-normalize-ai-model" -and $_.passed }).Count -eq 1)
        cliGenerateWorks = [bool](@($commands | Where-Object { $_.name -eq "npdev-generate-app" -and $_.passed }).Count -eq 1)
        cliReportBootstrapCallable = [bool]((Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath "NPDevCli/npdev_cli.py")).Contains("report") -and (Get-Content -Raw -LiteralPath (Join-Path $workspaceRootPath ".github/workflows/npdev-ci-validation.yml")).Contains("./npdev report bootstrap"))
        hardcodedDriveLettersCount = @($hardcodedMatches).Count
        powershellScriptCountInGradleCoreTasks = @($gradleMatches).Count
        linuxCommandExamplesPresent = $linuxExamplesPresent
        commands = @($commands)
        pathNeutralityScanScope = @($pathNeutralityScanScope)
        pathNeutralityExcludedPaths = @($pathNeutralityExcludedPaths)
        runtimeAbsoluteOutputPathsIgnored = $true
        # How many .py files had comments/docstrings stripped before scanning. 0 while .py files are
        # in scope means the stripper did not run (no python, or it failed) and those files were
        # scanned as raw text -- the fail-open path, which over-reports rather than under-reports.
        pythonProseStrippedFileCount = $script:PythonProseStrippedFileCount
        pathNeutralityScan = @($hardcodedMatches)
        gradleCoreTaskScan = @($gradleMatches)
        findings = @($findings)
        doesNotSolve = @(
            "Does not delete all PowerShell scripts.",
            "Does not require full CI migration to Gradle.",
            "Does not implement CP6 Gradle-native validation migration.",
            "Does not proceed to Checkpoint 6."
        )
    }

    $allCommandsPassed = @($commands | Where-Object { -not $_.passed }).Count -eq 0
    if ($report.cliToolExists -and $report.cliVersionWorks -and $report.cliModelValidationWorks -and $report.cliModelValidationUsesCanonicalSchema -and $report.cliInvalidModelRejected -and $report.cliAiNormalizeWorks -and $report.cliGenerateWorks -and $report.hardcodedDriveLettersCount -eq 0 -and $report.powershellScriptCountInGradleCoreTasks -eq 0 -and $report.linuxCommandExamplesPresent -and $allCommandsPassed) {
        $report.overallStatus = "passed"
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    $report | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

    $schemaValidationPath = "scripts/reports/tmp/portable-tooling-report-schema-validation.json"
    $ErrorActionPreference = "Continue"
    pwsh -NoProfile -File scripts/quality/Invoke-JsonSchemaValidation.ps1 -SchemaPath $SchemaPath -InstancePath $ReportPath -ReportPath $schemaValidationPath 2>$null | Out-Null
    $schemaExitCode = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaExitCode -ne 0) {
        Write-Error ("Portable tooling report failed schema validation. Report: " + $ReportPath)
        exit 1
    }
    if ($report.overallStatus -ne "passed") {
        Write-Error ("Portable tooling check failed. Report: " + $ReportPath)
        exit 1
    }

    Write-Host ("Portable tooling check passed. Report: " + $ReportPath)
    exit 0
}
finally {
    # 2026-08-23: this check runs the README's own documented commands, two of which write into
    # `build/` at the REPO ROOT -- `npdev normalize ai-model > build/npdev-normalized-model.json`
    # and `npdev generate app --output build/npdev-generated` (which also emits build/ArtifactNP).
    # The relative paths are deliberate: they are exactly what content/readme.json tells a user to
    # run, and rewriting them to an absolute external root would put a hardcoded drive letter in
    # the very script whose job is to refuse them. What was wrong is that the artefacts were LEFT
    # BEHIND, so this check violated CLAUDE.md's first rule ("NEVER write generated/build artifacts
    # inside this repo") every time it ran, and left 137 generated .java files in the tree.
    # That is not cosmetic: security-pattern-sweep.py's coverage_gaps() then found two unknown
    # module roots and failed the AI knowledge gate at steps [5/39] and [6/39] -- a gate turned red
    # by another gate's litter, with nothing pointing at the cause. Nothing reads these outputs;
    # only the commands' exit codes are asserted. So clean up what we created.
    foreach ($leftover in @("build/npdev-generated", "build/ArtifactNP", "build/npdev-normalized-model.json")) {
        $leftoverPath = Join-Path $workspaceRootPath $leftover
        if (Test-Path -LiteralPath $leftoverPath) {
            Remove-Item -LiteralPath $leftoverPath -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    # Only remove `build/` itself if this run left it empty -- never delete a directory that
    # already held something else.
    $buildDir = Join-Path $workspaceRootPath "build"
    if ((Test-Path -LiteralPath $buildDir) -and
        -not (Get-ChildItem -LiteralPath $buildDir -Force -ErrorAction SilentlyContinue)) {
        Remove-Item -LiteralPath $buildDir -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
