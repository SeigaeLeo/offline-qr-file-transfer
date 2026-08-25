$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$senderProject = Join-Path $projectRoot 'windows-sender-v1.7.1\QrTransferSender.csproj'
$testProject = Join-Path $projectRoot 'tests-v1.7.1\ProtocolTests.csproj'
$publishDirectory = Join-Path $projectRoot 'build\v1.7.1\win-x64'
$deliveryName = -join [char[]](0x53D1, 0x5E03, 0x6210, 0x54C1)
$deliveryDirectory = Join-Path (Join-Path $projectRoot $deliveryName) 'V1.7.1'

Write-Host '1/4 Run QTX1-W V1.7.1 protocol and timing tests'
dotnet run --project $testProject -c Release
if ($LASTEXITCODE -ne 0) { throw 'QTX1-W V1.7.1 tests failed.' }

Write-Host '2/4 Publish V1.7.1 self-contained Windows executable'
dotnet publish $senderProject -c Release -r win-x64 --self-contained true -o $publishDirectory
if ($LASTEXITCODE -ne 0) { throw 'V1.7.1 Windows publish failed.' }

Write-Host '3/4 Assemble V1.7.1 desktop deliverables'
New-Item -ItemType Directory -Path $deliveryDirectory -Force | Out-Null
$publishedExe = Get-ChildItem -LiteralPath $publishDirectory -Filter '*.exe' -File
if ($publishedExe.Count -ne 1) { throw "Expected one V1.7.1 executable, found $($publishedExe.Count)." }
Copy-Item -LiteralPath $publishedExe.FullName -Destination (Join-Path $deliveryDirectory $publishedExe.Name) -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'README-V1.7.1.md') -Destination (Join-Path $deliveryDirectory 'README-V1.7.1.md') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'QTX1W-protocol.md') -Destination (Join-Path $deliveryDirectory 'QTX1W-protocol.md') -Force

Write-Host '4/4 Verify single-file output'
$unexpected = Get-ChildItem -LiteralPath $publishDirectory -File | Where-Object Extension -ne '.exe'
if ($unexpected) { throw "Unexpected V1.7.1 publish files: $($unexpected.Name -join ', ')" }
$exe = Get-Item -LiteralPath (Join-Path $deliveryDirectory $publishedExe.Name)
$hash = (Get-FileHash -LiteralPath $exe.FullName -Algorithm SHA256).Hash
$shaText = "$($exe.Name)`r`nSHA-256: $hash`r`n`r`n手机端继续使用 V1.7 APK，本版本没有 Android 端改动。`r`n"
[System.IO.File]::WriteAllText((Join-Path $deliveryDirectory 'SHA256.txt'), $shaText, [System.Text.UTF8Encoding]::new($false))
Write-Host "V1.7.1 build complete: $($exe.FullName)"
Write-Host "SHA-256: $hash"
