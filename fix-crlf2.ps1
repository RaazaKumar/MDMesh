$files = @(
    'e:\RR Finpay Complete\AndroidLock\supervisor\apply.sh',
    'e:\RR Finpay Complete\AndroidLock\supervisor\rollback.sh'
)
foreach ($path in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $out = New-Object System.Collections.Generic.List[byte]
    $i = 0
    while ($i -lt $bytes.Length) {
        if ($i + 1 -lt $bytes.Length -and $bytes[$i] -eq 13 -and $bytes[$i+1] -eq 10) {
            $out.Add(10)
            $i += 2
        } else {
            $out.Add($bytes[$i])
            $i++
        }
    }
    [System.IO.File]::WriteAllBytes($path, $out.ToArray())
    Write-Host "Fixed: $path"
}
