# Roda o Gradle com o JDK 17 e o Android SDK instalados em C:\Users\Pedro\AndroidDev.
# Uso: .\g.ps1 :core:test      |      .\g.ps1 :app:assembleDebug
$env:JAVA_HOME = 'C:\Users\Pedro\AndroidDev\jdk17'
$env:ANDROID_HOME = 'C:\Users\Pedro\AndroidDev\sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& 'C:\Users\Pedro\AndroidDev\gradle-8.11.1\bin\gradle.bat' --console=plain @args
exit $LASTEXITCODE
