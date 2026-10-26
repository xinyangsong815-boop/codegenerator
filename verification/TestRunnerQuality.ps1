param([string]$RunnerScript = '', [string]$Report = 'verification/test-runner-quality.json')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $RunnerScript) { $RunnerScript = Join-Path $PSScriptRoot 'run-generated-tests.ps1' }
$runnerRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $RunnerScript) '..'))
New-Item -ItemType Directory -Force (Join-Path $runnerRoot 'build') | Out-Null
$work = Join-Path $projectRoot ('build/runner-quality-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$results = [Collections.Generic.List[object]]::new()
$global:QualityMavenMode = 'none'

# Only the runner orchestration is tested here. No Maven or application code executes.
function mvn.cmd {
    $global:LASTEXITCODE = 0
    if ($global:QualityMavenMode -eq 'none') { return }
    $pomArg = [array]::IndexOf($args, '-f')
    if ($pomArg -lt 0) { throw 'Missing fixture POM argument' }
    $fixture = Split-Path -Parent $args[$pomArg + 1]
    $reports = Join-Path $fixture 'target/surefire-reports'
    New-Item -ItemType Directory -Force $reports | Out-Null
    $tests = 3
    $skips = if ($global:QualityMavenMode -eq 'skipped') { 3 } else { 0 }
    $failures = if ($global:QualityMavenMode -eq 'failed') { 1 } else { 0 }
    [IO.File]::WriteAllText((Join-Path $reports 'TEST-synthetic.xml'), "<testsuite tests='$tests' skipped='$skips' failures='$failures' errors='0' />")
}
function Check([string]$Name, [scriptblock]$Action) {
    try { & $Action; $results.Add([ordered]@{name=$Name;passed=$true}) }
    catch { $results.Add([ordered]@{name=$Name;passed=$false;error=$_.Exception.Message}) }
}
function Fixture([string]$Name) {
    $matrix = Join-Path $work $Name
    $fixture = Join-Path $matrix 'module'
    New-Item -ItemType Directory -Force $fixture | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'pom.xml'), '<project/>')
    return $matrix
}
function MustFail([string]$Matrix) {
    $failed = $false
    try { & $RunnerScript -Matrix $Matrix -SummaryFile (Join-Path $Matrix 'summary.json') }
    catch {
        if ($_.Exception.Message -notmatch 'No generated Maven projects|generated project tests failed') { throw }
        $failed = $true
    }
    if (-not $failed) { throw 'Runner accepted missing, skipped or failed execution' }
}
Check 'empty-matrix-is-not-a-successful-test-run' {
    $empty = Join-Path $work 'empty'
    New-Item -ItemType Directory -Force $empty | Out-Null
    $global:QualityMavenMode = 'none'
    MustFail $empty
}
Check 'stale-surefire-reports-cannot-prove-current-execution' {
    $matrix = Fixture 'stale'
    $old = Join-Path $matrix 'module/target/surefire-reports'
    New-Item -ItemType Directory -Force $old | Out-Null
    [IO.File]::WriteAllText((Join-Path $old 'TEST-old.xml'), "<testsuite tests='99' skipped='0' failures='0' errors='0' />")
    $global:QualityMavenMode = 'none'
    MustFail $matrix
}
Check 'all-skipped-tests-are-not-executed-passes' {
    $global:QualityMavenMode = 'skipped'
    MustFail (Fixture 'skipped')
}
Check 'assertion-failures-block-the-run' {
    $global:QualityMavenMode = 'failed'
    MustFail (Fixture 'failed')
}
Check 'fresh-executed-results-are-recorded-as-an-array-even-for-one-project' {
    $matrix = Fixture 'fresh'
    $global:QualityMavenMode = 'passed'
    & $RunnerScript -Matrix $matrix -SummaryFile (Join-Path $matrix 'summary.json')
    $raw = [IO.File]::ReadAllText((Join-Path $matrix 'summary.json'))
    if (-not $raw.TrimStart().StartsWith('[')) { throw 'Single project summary lost its array schema' }
    $rows = ConvertFrom-Json -InputObject $raw
    if ($rows[0].executed -ne 3 -or $rows[0].skipped -ne 0) { throw 'Executed test counts are incorrect' }
}
$failures = @($results | Where-Object { -not $_.passed }).Count
$output = if ([IO.Path]::IsPathRooted($Report)) { $Report } else { Join-Path $projectRoot $Report }
ConvertTo-Json -InputObject ([ordered]@{tests=$results.Count;passed=($results.Count-$failures);failed=$failures;scope='Synthetic runner orchestration only; not generated business test evidence';results=@($results.ToArray())}) -Depth 8 | Set-Content -LiteralPath $output -Encoding utf8
Write-Output "Test runner quality: $($results.Count-$failures)/$($results.Count)"
if ($failures -gt 0) { throw 'Test runner quality checks failed' }
