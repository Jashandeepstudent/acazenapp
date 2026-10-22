# Downloads the Gradle distribution named in gradle-wrapper.properties (if needed)
# and prints the path to its gradle.bat. Used by gradlew.bat when there is no wrapper jar.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$props = Join-Path $PSScriptRoot 'gradle-wrapper.properties'
$url = ((Get-Content $props | Where-Object { $_ -like 'distributionUrl=*' }) -replace '^distributionUrl=', '' -replace '\\', '').Trim()
$name = (($url -split '/')[-1]) -replace '\.zip$', ''
$ver = $name -replace '^gradle-', '' -replace '-(bin|all)$', ''
$base = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
$dest = Join-Path $base "wrapper\bootstrap\$name"
$bin = Join-Path $dest "gradle-$ver\bin\gradle.bat"

if (-not (Test-Path $bin)) {
    [Console]::Error.WriteLine("Downloading $url")
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $zip = Join-Path $dest "$name.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $dest -Force
    Remove-Item $zip
}
Write-Output $bin
