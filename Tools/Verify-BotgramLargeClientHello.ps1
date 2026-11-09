param([string]$DependencyRoot = 'D:\BotgramBuild', [string]$Endpoint = '')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp/large-client-hello/' + [Guid]::NewGuid().ToString('N'))))
if (!$output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host checks must remain on D:' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
& javac -encoding UTF-8 -d $output `
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/BotgramLargeClientHelloSocketFactory.java') `
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/BotgramTlsDiagnostics.java') `
    (Join-Path $PSScriptRoot 'tests/BotgramLargeClientHelloSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Large ClientHello host-test compilation failed' }
$arguments = @('-cp', $output, 'BotgramLargeClientHelloSmoke')
if ($Endpoint) { $arguments += $Endpoint }
& java @arguments
if ($LASTEXITCODE -ne 0) { throw 'Large ClientHello verification failed' }
