# Dot-source this to point a PowerShell session at the toolchain on this drive:
#   . A:\AndroidLinuxVM\env.ps1
# Everything (SDK, emulator images, Gradle caches, temp files) stays on this drive.
$root = $PSScriptRoot
$env:ANDROID_HOME = "$root\sdk"
$env:ANDROID_SDK_ROOT = "$root\sdk"
$env:ANDROID_AVD_HOME = "$root\avd"
$env:ANDROID_EMULATOR_HOME = "$root\avd-home"
$env:GRADLE_USER_HOME = "$root\gradle-home"
$env:TEMP = "$root\tmp"
$env:TMP = "$root\tmp"
$env:Path = "$root\sdk\platform-tools;$root\sdk\emulator;$root\sdk\cmdline-tools\latest\bin;$root\gradle-8.11.1\bin;$env:Path"
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME, $env:ANDROID_EMULATOR_HOME, $env:TEMP | Out-Null
