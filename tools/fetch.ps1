# Downloads build tools for the APK: JDK 17, Android command-line tools, Gradle.
param([string]$What, [string]$Dest)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-File($url, $file) {
    Write-Host "  downloading: $url"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file
}

if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Path $Dest | Out-Null }

switch ($What) {
    'jdk' {
        $zip = Join-Path $Dest 'jdk.zip'
        $tmp = Join-Path $Dest 'jdk_tmp'
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $zip
        Write-Host '  unpacking...'
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
        Move-Item $inner.FullName (Join-Path $Dest 'jdk')
        Remove-Item $tmp -Recurse -Force
        Remove-Item $zip -Force
    }
    'cmdline' {
        $zip = Join-Path $Dest 'cmdline.zip'
        $tmp = Join-Path $Dest 'cmd_tmp'
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
        Write-Host '  unpacking...'
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $dst = Join-Path $Dest 'android-sdk\cmdline-tools'
        New-Item -ItemType Directory -Path $dst -Force | Out-Null
        Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $dst 'latest')
        Remove-Item $tmp -Recurse -Force
        Remove-Item $zip -Force
    }
    'gradle' {
        $zip = Join-Path $Dest 'gradle.zip'
        Get-File 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' $zip
        Write-Host '  unpacking...'
        Expand-Archive -Path $zip -DestinationPath $Dest -Force
        Remove-Item $zip -Force
    }
    default { throw "unknown parameter: $What" }
}
