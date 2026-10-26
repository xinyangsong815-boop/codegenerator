#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=${BASH_SOURCE[0]%/*}
if [[ $SCRIPT_DIR == "${BASH_SOURCE[0]}" ]]; then SCRIPT_DIR=.; fi
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR" && pwd)
cd -- "$ROOT"
JAVAC_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
bash "$ROOT/build.sh"
CP=$(cat build/verification-classpath.txt)
mkdir -p build/probe
"$JAVAC_BIN" -encoding UTF-8 -source 17 -target 17 -cp "$CP" -d build/probe verification/RecoveryProbe.java verification/GeneratorRegressionSuite.java verification/ProductionAuditSuite.java verification/UpgradeAuditSuite.java verification/ContractUpgradeSuite.java verification/RegressionUpgradeSuite.java verification/WorkflowUpgradeSuite.java verification/ProductionUpgradeSuite.java verification/NextContractUpgradeSuite.java verification/GeneratedNextSuite.java verification/RepositoryUpgradeSuite.java verification/ProductionNextSuite.java verification/ReleaseSafetySuite.java verification/GeneratedQualitySuite.java verification/BackendQualitySuite.java
"$JAVA_BIN" -Xmx512m -cp "$CP:build/probe" cn.bank.devtool.GeneratorRegressionSuite . verification/generator-tests.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.ProductionAuditSuite . verification/production-audit.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.UpgradeAuditSuite . verification/upgrade-audit.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.ContractUpgradeSuite . verification/contractupgrade.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.RegressionUpgradeSuite build/regression-upgrade-suite
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.WorkflowUpgradeSuite 17 verification/workflowupgrade
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.ProductionUpgradeSuite . verification/productionupgrade
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.NextContractUpgradeSuite . verification/nextcontractupgradesuite.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.GeneratedNextSuite . verification/generatednextsuite.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.RepositoryUpgradeSuite 17 verification/repositorynext
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.ProductionNextSuite . verification/productionnextsuite.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.ReleaseSafetySuite . verification/release-safety-tests.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.GeneratedQualitySuite . verification/generatedqualitysuite.json
"$JAVA_BIN" -Xmx768m -cp "$CP:build/probe" cn.bank.devtool.BackendQualitySuite . verification/backend-quality-tests.json
"$JAVA_BIN" -Xmx512m -cp "$CP:build/probe" RecoveryProbe . verification/generated matrix
if command -v node >/dev/null; then
    node verification/client-and-ui-tests.mjs verification/generated verification/client-tests.json
    node verification/website-state-tests.mjs src/main/resources/static verification/website-state-tests.json
    node verification/workflow-upgrade-tests.mjs src/main/resources/static verification/workflow-upgrade-tests.json
    node verification/repository-ui-tests.mjs src/main/resources/static verification/repository-ui-tests.json
    node verification/workflow-next-tests.mjs src/main/resources/static verification/workflow-next-tests.json
else
    echo 'INCOMPLETE: Node.js is required; client tests were not executed.' >&2
    exit 2
fi
