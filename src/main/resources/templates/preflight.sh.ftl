#!/usr/bin/env bash
set -euo pipefail
: "${r'${DB_URL:?Set DB_URL}'}"
: "${r'${DB_USER:?Set DB_USER}'}"
: "${r'${DB_PASSWORD:?Set DB_PASSWORD}'}"
case "$DB_URL" in jdbc:mysql://*) ;; *) echo 'DB_URL must be an explicit MySQL JDBC URL.' >&2; exit 1;; esac
JAVA_BIN="${r'${JAVA_HOME:+$JAVA_HOME/bin/}'}java"
VERSION=$("$JAVA_BIN" -version 2>&1)
<#if modern>
if [[ ! "$VERSION" =~ version\ \"17\. ]]; then echo 'This release requires Java ${targetJava}.' >&2; exit 1; fi
<#else>
if [[ ! "$VERSION" =~ version\ \"1\.8\. ]]; then echo 'This release requires Java 8.' >&2; exit 1; fi
</#if>
if [[ $# -ne 1 || ! -f "$1" ]]; then echo 'Usage: bash deployment/preflight.sh /absolute/path/application.jar' >&2; exit 1; fi
echo '基础配置预检通过；尚未执行数据库、认证权限、迁移、压力或恢复验收。'
