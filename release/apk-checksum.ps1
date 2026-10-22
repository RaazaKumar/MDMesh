param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ApkPath
)

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK file not found: $ApkPath"
    exit 1
}

# Find apksigner.bat in Android SDK
$sdkRoots = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    "$env:LOCALAPPDATA\Android\Sdk"
) | Where-Object { $_ -and (Test-Path $_) }

$apksigner = $null
foreach ($root in $sdkRoots) {
    $found = Get-ChildItem -Path "$root\build-tools" -Filter "apksigner.bat" -Recurse -ErrorAction SilentlyContinue |
             Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) {
        $apksigner = $found.FullName
        break
    }
}

if (-not $apksigner) {
    Write-Error "apksigner.bat not found in Android SDK. Please ensure build-tools are installed."
    exit 1
}

$certOutput = & $apksigner verify --print-certs $ApkPath 2>$null
$match = [regex]::Match($certOutput, 'SHA-256 digest:\s*([0-9a-fA-F\s:]+)')

if (-not $match.Success) {
    Write-Error "No signing certificate found in $ApkPath"
    exit 1
}

$hex = ($match.Groups[1].Value -replace '[\s:]', '').ToLower()

# Convert hex to byte array
$bytes = [byte[]]::new($hex.Length / 2)
for ($i = 0; $i -lt $hex.Length; $i += 2) {
    $bytes[$i / 2] = [Convert]::ToByte($hex.Substring($i, 2), 16)
}

# Base64url encode without padding
$base64Url = [Convert]::ToBase64String($bytes) -replace '\+', '-' -replace '/', '_' -replace '=', ''
Write-Output $base64Url
