# Roda o Gradle no Windows com um JDK 17 e um Android SDK locais (sem precisar deles no PATH).
# Uso: .\g.ps1 :core:test      |      .\g.ps1 :app:assembleRelease
# Os caminhos vêm de JAVA_HOME / ANDROID_HOME / GRADLE se definidos; senão, da pasta AndroidDev do usuário.
$dev = Join-Path $env:USERPROFILE 'AndroidDev'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $dev 'jdk17' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $dev 'sdk' }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = if ($env:GRADLE) { $env:GRADLE } else { Join-Path $dev 'gradle-8.11.1\bin\gradle.bat' }
if (-not (Test-Path $gradle)) { $gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
& $gradle --console=plain @args
exit $LASTEXITCODE
