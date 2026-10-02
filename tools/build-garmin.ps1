[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$KeyPath,
    [string]$SdkPath,
    [ValidateSet('epix2pro42mm', 'epix2pro47mm', 'epix2pro51mm')]
    [string[]]$Device = @('epix2pro42mm', 'epix2pro47mm', 'epix2pro51mm')
)
$ErrorActionPreference = 'Stop'
if (!$SdkPath) {
    $SdkPath = (Get-Content -Raw (Join-Path $env:APPDATA 'Garmin\ConnectIQ\current-sdk.cfg')).Trim()
}
$compiler = Join-Path $SdkPath 'bin\monkeyc.bat'
if (!(Test-Path -LiteralPath $compiler)) { throw "Compiler not found: $compiler" }
$resolvedKey = (Resolve-Path -LiteralPath $KeyPath).Path
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build\garmin'
New-Item -ItemType Directory -Force -Path $output | Out-Null
Push-Location (Join-Path $root 'garmin-epix-pro')
try {
    foreach ($target in $Device) {
        $artifact = Join-Path $output "Apophenia-$target.prg"
        & $compiler -f monkey.jungle -d $target -y $resolvedKey -o $artifact -w
        if ($LASTEXITCODE -ne 0) { throw "Build failed for $target" }
        Get-FileHash -LiteralPath $artifact -Algorithm SHA256
    }
} finally { Pop-Location }
