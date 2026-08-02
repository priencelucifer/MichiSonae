$ErrorActionPreference = "Stop"

$androidRoot = $PSScriptRoot
$wrapperDirectory = Join-Path $androidRoot "gradle/wrapper"
$expectedWrapperHash = ((Get-Content (Join-Path $wrapperDirectory "gradle-wrapper.jar.sha256") -Raw) -split "\s+")[0].ToLowerInvariant()
$actualWrapperHash = (Get-FileHash (Join-Path $wrapperDirectory "gradle-wrapper.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualWrapperHash -ne $expectedWrapperHash) {
    throw "Gradle wrapper JAR checksum mismatch"
}

$wrapperProperties = Get-Content (Join-Path $wrapperDirectory "gradle-wrapper.properties") -Raw
if ($wrapperProperties -notmatch "(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-\d+\.\d+(?:\.\d+)?-bin\.zip$") {
    throw "Gradle distribution must use a fixed release version"
}
if ($wrapperProperties -notmatch "(?m)^distributionSha256Sum=[0-9a-f]{64}$") {
    throw "Gradle distribution must have a SHA-256 checksum"
}

$dynamicVersion = '(?i)(?:latest|snapshot|\+|[\[\]\(\),])'
$gradleFiles = @(
    Get-Item (Join-Path $androidRoot "build.gradle.kts")
    Get-Item (Join-Path $androidRoot "app/build.gradle.kts")
)
foreach ($file in $gradleFiles) {
    foreach ($line in Get-Content $file.FullName) {
        if ($line -match '(?:implementation|testImplementation|androidTestImplementation|debugImplementation|platform)\("([^"]+)"\)') {
            $coordinate = $Matches[1]
            if ($coordinate -match $dynamicVersion) {
                throw "Dynamic dependency coordinate in $($file.FullName): $coordinate"
            }
            $parts = $coordinate.Split(":")
            $bomManagedCompose = $parts.Count -eq 2 -and $coordinate.StartsWith("androidx.compose.")
            if ($parts.Count -ne 3 -and -not $bomManagedCompose) {
                throw "Dependency must have an exact version or be managed by the Compose BOM: $coordinate"
            }
        }
        if ($line -match '\bversion\s+"([^"]+)"' -and $Matches[1] -match $dynamicVersion) {
            throw "Gradle plugins must use fixed release versions: $($Matches[1])"
        }
    }
}

$androidNamespace = "http://schemas.android.com/apk/res/android"
[xml]$manifest = Get-Content (Join-Path $androidRoot "app/src/main/AndroidManifest.xml") -Raw
$application = $manifest.manifest.application
if ($application.GetAttribute("allowBackup", $androidNamespace) -ne "false") {
    throw "Android backups must remain disabled for local diagnostic and credential data"
}
if ($application.GetAttribute("usesCleartextTraffic", $androidNamespace) -ne "false") {
    throw "Cleartext application traffic must remain disabled"
}
$uploadService = @(@($application.service) | Where-Object {
    $_.GetAttribute("name", $androidNamespace) -eq ".ObservationUploadJobService"
})
if ($uploadService.Count -ne 1 -or
    $uploadService[0].GetAttribute("permission", $androidNamespace) -ne "android.permission.BIND_JOB_SERVICE") {
    throw "The upload job service must remain protected by BIND_JOB_SERVICE"
}
$bootReceiver = @(@($application.receiver) | Where-Object {
    $_.GetAttribute("name", $androidNamespace) -eq ".BootRecoveryReceiver"
})
if ($bootReceiver.Count -ne 1 -or $bootReceiver[0].GetAttribute("exported", $androidNamespace) -ne "false") {
    throw "The boot recovery receiver must not be exported"
}

Write-Host "Android release inputs use checksummed Gradle and fixed dependency versions."
