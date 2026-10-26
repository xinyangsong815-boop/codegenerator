param([string]$Matrix = 'verification/generated', [string]$SummaryFile = '')
$ErrorActionPreference = 'Stop'
$generatorRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SummaryFile) { $SummaryFile = if ($Matrix -eq 'verification/generated') { 'verification/generated-test-summary.json' } else { 'verification/boundary-test-summary.json' } }
$matrixRoot = if ([IO.Path]::IsPathRooted($Matrix)) { $Matrix } else { Join-Path $generatorRoot $Matrix }
$summaryPath = if ([IO.Path]::IsPathRooted($SummaryFile)) { $SummaryFile } else { Join-Path $generatorRoot $SummaryFile }
Push-Location -LiteralPath $generatorRoot
try {
    $summary = @()
    foreach ($fixture in Get-ChildItem -LiteralPath $matrixRoot -Directory) {
        $pom = Join-Path $fixture.FullName 'pom.xml'
        if (-not (Test-Path -LiteralPath $pom)) { continue }
        $log = Join-Path $generatorRoot ('build/generated-' + $fixture.Name + '.log')
        $reports = Join-Path $fixture.FullName 'target/surefire-reports'
        if (Test-Path -LiteralPath $reports) {
            $previous = @(Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File)
            if ($previous.Count -gt 0) {
                $archive = Join-Path $generatorRoot ('build/surefire-history/' + $fixture.Name + '-' + [guid]::NewGuid().ToString('N'))
                New-Item -ItemType Directory -Path $archive -Force | Out-Null
                foreach ($oldReport in $previous) {
                    Move-Item -LiteralPath $oldReport.FullName -Destination (Join-Path $archive $oldReport.Name)
                }
            }
        }
        & mvn.cmd -o -B '-Dstyle.color=never' -f $pom test > $log 2>&1
        $exitCode = $LASTEXITCODE
        $totals = @{tests=0;failures=0;errors=0;skipped=0}
        if (Test-Path -LiteralPath $reports) {
            foreach ($report in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml') {
                [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
                foreach ($key in @('tests','failures','errors','skipped')) { $totals[$key] += [int]$xml.testsuite.GetAttribute($key) }
            }
        }
        $row = [ordered]@{fixture=$fixture.Name;exitCode=$exitCode;tests=$totals.tests;failures=$totals.failures;errors=$totals.errors;skipped=$totals.skipped;executed=($totals.tests-$totals.skipped)}
        $summary += $row
        Write-Output ($row | ConvertTo-Json -Compress)
    }
    ConvertTo-Json -InputObject @($summary) -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    if ($summary.Count -eq 0) { throw 'No generated Maven projects found; no tests were executed.' }
    if (@($summary | Where-Object { $_.exitCode -ne 0 -or $_.failures -ne 0 -or $_.errors -ne 0 -or $_.executed -le 0 }).Count -ne 0) { throw 'One or more generated project tests failed or had no executed tests; inspect build/generated-*.log.' }
} finally { Pop-Location }

