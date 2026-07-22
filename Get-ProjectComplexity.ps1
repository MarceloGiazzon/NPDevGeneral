# Get-ProjectComplexity.ps1
# Run this script in the root directory of your project.

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "       PROJECT COMPLEXITY SCANNER            " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# 1. Define target code extensions (adjust this list based on your language)
$Extensions = @("*.cs", "*.js", "*.ts", "*.py", "*.go", "*.java", "*.cpp", "*.h", "*.rb", "*.php", "*.rs")

# Find all relevant code files, ignoring common package/build folders
Write-Host "Scanning directory for code files..." -ForegroundColor Yellow
$Files = Get-ChildItem -Recurse -Include $Extensions -ErrorAction SilentlyContinue | 
         Where-Object { $_.FullName -notmatch "node_modules|bin|obj|dist|\.git|vendor|target|build" }

if ($Files.Count -eq 0) {
    Write-Host "No matching code files found. Check your file extensions in the script!" -ForegroundColor Red
    return
}

# Initialize metrics
$TotalLines = 0
$TotalFiles = $Files.Count
$ComplexFilesList = @()

# Complexity indicators to hunt for
$ComplexityPatterns = @{
    "Nested Loops / Conditions" = "for\s*\(|while\s*\(|if\s*\(|foreach\s*\("
    "Catch Blocks (Error Handling)" = "catch\s*"
    "Async/Promises"               = "async|await|Promise"
}

Write-Host "Analyzing $TotalFiles files..." -ForegroundColor Yellow

foreach ($File in $Files) {
    try {
        $Lines = Get-Content $File.FullName -ErrorAction SilentlyContinue
        $LineCount = $Lines.Count
        $TotalLines += $LineCount

        # Check for density of complexity markers
        $MarkerCount = 0
        foreach ($Key in $ComplexityPatterns.Keys) {
            $Pattern = $ComplexityPatterns[$Key]
            $Matches = $Lines | Select-String -Pattern $Pattern -AllMatches
            if ($Matches) { $MarkerCount += $Matches.Matches.Count }
        }

        # If a file is huge or dense with logic, flag it
        if ($LineCount -gt 300 -or $MarkerCount -gt 25) {
            $ComplexFilesList += [PSCustomObject]@{
                FileName    = $File.Name
                Lines       = $LineCount
                LogicTokens = $MarkerCount
                Path        = $File.FullName.Replace($PSScriptRoot, ".")
            }
        }
    }
    catch {
        # Skip files that can't be read
    }
}

# 2. Output the Summary Report
Write-Host "`n================ RESULTS ================" -ForegroundColor Green
Write-Host "Total Code Files:      $TotalFiles"
Write-Host "Total Lines of Code:   $TotalLines"
Write-Host "Avg Lines per File:    $([Math]::Round($TotalLines / $TotalFiles, 1))"

# Quick size-based complexity rating
$ComplexityRating = "Low"
$Color = "Green"
if ($TotalLines -gt 10000) { $ComplexityRating = "Very High"; $Color = "Red" }
elseif ($TotalLines -gt 5000) { $ComplexityRating = "High"; $Color = "Red" }
elseif ($TotalLines -gt 1000) { $ComplexityRating = "Medium"; $Color = "Yellow" }

Write-Host "Size-Based Complexity: $ComplexityRating" -ForegroundColor $Color

# 3. List Potential Problem Areas
if ($ComplexFilesList.Count -gt 0) {
    Write-Host "`nTop 'Complex' Files to Watch (High line counts or dense logic):" -ForegroundColor Orange
    $ComplexFilesList | Sort-Object Lines -Descending | Select-Object -First 5 | Format-Table FileName, Lines, LogicTokens, Path -AutoSize
} else {
    Write-Host "`nNo individual files flagged as highly complex. Codebase seems modular!" -ForegroundColor Green
}
Write-Host "=========================================" -ForegroundColor Green
