Write-Host "Installing Temurin JDK 17 via winget..."
winget install EclipseAdoptium.Temurin.17.JDK --silent --accept-package-agreements --accept-source-agreements

Write-Host "Set JAVA_HOME to the installed JDK 17 path (current shell):"
$jdkPath = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1

if ($null -ne $jdkPath) {
    $javaHome = Join-Path $jdkPath.FullName "bin\.."
    $resolvedJavaHome = (Resolve-Path $javaHome).Path
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $resolvedJavaHome, "User")
    Write-Host "JAVA_HOME persisted for user: $resolvedJavaHome"
    Write-Host "Restart terminal, then run: .\gradlew.bat clean test"
} else {
    Write-Host "JDK folder not discovered automatically. Please set JAVA_HOME manually to JDK 17."
}
