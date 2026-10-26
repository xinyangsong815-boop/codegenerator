param([switch]$GeneratedProjects)
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    & ./build.ps1
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    $javacBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
    $classpath = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'build/verification-classpath.txt'))
    New-Item -ItemType Directory -Path build/probe -Force | Out-Null
    & $javacBin -encoding UTF-8 -source 17 -target 17 -cp $classpath -d build/probe verification/RecoveryProbe.java verification/GeneratorRegressionSuite.java verification/ProductionAuditSuite.java verification/UpgradeAuditSuite.java verification/ContractUpgradeSuite.java verification/RegressionUpgradeSuite.java verification/WorkflowUpgradeSuite.java verification/ProductionUpgradeSuite.java verification/NextContractUpgradeSuite.java verification/GeneratedNextSuite.java verification/RepositoryUpgradeSuite.java verification/ProductionNextSuite.java verification/ReleaseSafetySuite.java verification/GeneratedQualitySuite.java verification/BackendQualitySuite.java
    if ($LASTEXITCODE -ne 0) { throw 'Java17 verification source compilation failed.' }
    & $javaBin -Xmx512m -cp ($classpath+';build/probe') cn.bank.devtool.GeneratorRegressionSuite . verification/generator-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'Generator verification failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.ProductionAuditSuite . verification/production-audit.json
    if ($LASTEXITCODE -ne 0) { throw 'Production audit failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.UpgradeAuditSuite . verification/upgrade-audit.json
    if ($LASTEXITCODE -ne 0) { throw 'Upgrade audit failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.ContractUpgradeSuite . verification/contractupgrade.json
    if ($LASTEXITCODE -ne 0) { throw 'ContractUpgradeSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.RegressionUpgradeSuite build/regression-upgrade-suite
    if ($LASTEXITCODE -ne 0) { throw 'RegressionUpgradeSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.WorkflowUpgradeSuite 17 verification/workflowupgrade
    if ($LASTEXITCODE -ne 0) { throw 'WorkflowUpgradeSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.ProductionUpgradeSuite . verification/productionupgrade
    if ($LASTEXITCODE -ne 0) { throw 'ProductionUpgradeSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.NextContractUpgradeSuite . verification/nextcontractupgradesuite.json
    if ($LASTEXITCODE -ne 0) { throw 'NextContractUpgradeSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.GeneratedNextSuite . verification/generatednextsuite.json
    if ($LASTEXITCODE -ne 0) { throw 'GeneratedNextSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.RepositoryUpgradeSuite 17 verification/repositorynext
    if ($LASTEXITCODE -ne 0) { throw 'RepositoryUpgradeSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.ProductionNextSuite . verification/productionnextsuite.json
    if ($LASTEXITCODE -ne 0) { throw 'ProductionNextSuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.ReleaseSafetySuite . verification/release-safety-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'ReleaseSafetySuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.GeneratedQualitySuite . verification/generatedqualitysuite.json
    if ($LASTEXITCODE -ne 0) { throw 'GeneratedQualitySuite failed.' }
    & $javaBin -Xmx768m -cp ($classpath+';build/probe') cn.bank.devtool.BackendQualitySuite . verification/backend-quality-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'BackendQualitySuite failed.' }
    & $javaBin -Xmx512m -cp ($classpath+';build/probe') RecoveryProbe . verification/generated matrix
    if ($LASTEXITCODE -ne 0) { throw 'Generation matrix failed.' }
    & node verification/client-and-ui-tests.mjs verification/generated verification/client-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'Client verification failed.' }
    & node verification/website-state-tests.mjs src/main/resources/static verification/website-state-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'Composed website state verification failed.' }
    & node verification/workflow-upgrade-tests.mjs src/main/resources/static verification/workflow-upgrade-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'Workflow UI upgrade failed.' }
    & node verification/repository-ui-tests.mjs src/main/resources/static verification/repository-ui-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'Repository UI tests failed.' }
    & node verification/workflow-next-tests.mjs src/main/resources/static verification/workflow-next-tests.json
    if ($LASTEXITCODE -ne 0) { throw 'Workflow race checks failed.' }
    & ./verification/TestRunnerQuality.ps1
    if ($GeneratedProjects) {
        & ./verification/run-generated-tests.ps1
        & $javacBin -encoding UTF-8 -source 17 -target 17 -cp $classpath -d build/probe verification/GeneratorCodeAudit.java
        if ($LASTEXITCODE -ne 0) { throw 'Code audit compilation failed.' }
        & $javaBin -Xmx768m -cp ($classpath+';build/probe') GeneratorCodeAudit . verification/audit-generated-after verification/code-audit.json
        if ($LASTEXITCODE -ne 0) { throw 'Generated code audit failed.' }
        & ./verification/run-generated-tests.ps1 -Matrix verification/audit-generated-after
    }
} finally { Pop-Location }
