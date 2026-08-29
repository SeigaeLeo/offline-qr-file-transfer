$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$senderProject = Join-Path $projectRoot 'windows-sender-v1.8.4\QrTransferSender.csproj'
$testProject = Join-Path $projectRoot 'tests-v1.8.4\ProtocolTests.csproj'
$publishDirectory = Join-Path $projectRoot 'build\v1.8.4\win-x64'
$deliveryName = -join [char[]](0x53D1, 0x5E03, 0x6210, 0x54C1)
$deliveryDirectory = Join-Path (Join-Path $projectRoot $deliveryName) 'V1.8.4'

Write-Host '1/4 Run V1.8.4 release protocol, DPI raster and quad-page tests'
dotnet run --project $testProject -c Release
if ($LASTEXITCODE -ne 0) { throw 'V1.8.4 release tests failed.' }

Write-Host '2/4 Publish self-contained Windows executable'
dotnet publish $senderProject -c Release -r win-x64 --self-contained true -o $publishDirectory
if ($LASTEXITCODE -ne 0) { throw 'V1.8.4 Windows publish failed.' }

Write-Host '3/4 Assemble desktop deliverables'
New-Item -ItemType Directory -Path $deliveryDirectory -Force | Out-Null
$publishedExe = Get-ChildItem -LiteralPath $publishDirectory -Filter '*.exe' -File
if ($publishedExe.Count -ne 1) { throw "Expected one executable, found $($publishedExe.Count)." }
Copy-Item -LiteralPath $publishedExe.FullName -Destination (Join-Path $deliveryDirectory $publishedExe.Name) -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'README-V1.8.4.md') -Destination (Join-Path $deliveryDirectory 'README-V1.8.4.md') -Force

Write-Host '4/4 Verify single-file output'
$unexpected = Get-ChildItem -LiteralPath $publishDirectory -File | Where-Object Extension -ne '.exe'
if ($unexpected) { throw "Unexpected publish files: $($unexpected.Name -join ', ')" }
$exe = Get-Item -LiteralPath (Join-Path $deliveryDirectory $publishedExe.Name)
$hash = (Get-FileHash -LiteralPath $exe.FullName -Algorithm SHA256).Hash
$hashLines = @($exe.Name, "SHA-256: $hash", '')
$apk = Get-ChildItem -LiteralPath $deliveryDirectory -Filter '*.apk' -File | Select-Object -First 1
if ($apk) {
    $apkHash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
    $hashLines += @($apk.Name, "SHA-256: $apkHash", '')
}
$hashLines += 'V1.8.4 release: native high DPI, integer QR modules, quad decode and rotating automatic-retry whitening.'
[System.IO.File]::WriteAllLines((Join-Path $deliveryDirectory 'SHA256.txt'), $hashLines, [System.Text.UTF8Encoding]::new($false))
Write-Host "V1.8.4 release EXE ready: $($exe.FullName)"
Write-Host "SHA-256: $hash"
