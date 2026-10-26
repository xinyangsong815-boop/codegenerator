@echo off
setlocal
cd /d "%~dp0"
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
set "APP_JAR=%~dp0offline-dev-assistant-2.1.1-rebuild.8-java17.jar"
if not exist "%APP_JAR%" set "APP_JAR=%~dp0dist\offline-dev-assistant-2.1.1-rebuild.8-java17.jar"
if not exist "%APP_JAR%" (
  echo Application JAR is missing. Build the source package first.
  exit /b 1
)
"%JAVA_BIN%" -Xms64m -Xmx512m "-Dfile.encoding=UTF-8" -jar "%APP_JAR%" %*
exit /b %ERRORLEVEL%
