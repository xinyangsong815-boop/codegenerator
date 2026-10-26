$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    $javacBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
    New-Item -ItemType Directory -Path build/bootstrap -Force | Out-Null
    & $javacBin -encoding UTF-8 -source 17 -target 17 -d build/bootstrap tools/OfflineBuild.java
    if ($LASTEXITCODE -ne 0) { throw 'Offline build bootstrap compilation failed.' }
    & $javaBin -Xmx512m -cp build/bootstrap OfflineBuild $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { throw 'Offline Java17 source build failed.' }
} finally { Pop-Location }
