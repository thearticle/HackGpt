param([switch]$SmokeTest)
$ErrorActionPreference = 'Stop'
$appDir = Join-Path $env:LOCALAPPDATA 'HackGpt-Tray'
[void][IO.Directory]::CreateDirectory($appDir)
$tokenFile = Join-Path $env:LOCALAPPDATA 'HackGpt-codex-remote-token'
$codex = Join-Path $env:LOCALAPPDATA 'Programs\OpenAI\Codex\bin\codex.exe'
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$project = 'C:\Users\Ojan\Documents\ChatGPT\android llm'
$endpoint = 'wss://desktop-5inlh0t.tail3c5fd0.ts.net:8443/'

function Get-Server {
    $listener = Get-NetTCPConnection -LocalPort 4500 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$listener) { return $null }
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if (!$owner) { return $null }
    if ($owner.Name -ne 'codex.exe' -or $owner.CommandLine -notmatch 'app-server' -or
        $owner.CommandLine -notmatch [regex]::Escape($tokenFile) -or
        $owner.CommandLine -notmatch 'ws://127\.0\.0\.1:4500') {
        throw 'Port 4500 belongs to another server. It was left running.'
    }
    return $owner
}
function Enable-Connection {
    if (!(Test-Path -LiteralPath $codex)) { throw 'Codex CLI was not found.' }
    if (!(Test-Path -LiteralPath $tokenFile) -or (Get-Item -LiteralPath $tokenFile).Length -eq 0) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        [IO.File]::WriteAllText($tokenFile, [Convert]::ToBase64String($bytes))
    }
    if (!(Get-Server)) {
        $arguments = 'app-server --listen ws://127.0.0.1:4500 --ws-auth capability-token --ws-token-file "' + $tokenFile + '"'
        Start-Process -FilePath $codex -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $appDir 'server.log') -RedirectStandardError (Join-Path $appDir 'server-error.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        while (!(Get-Server)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Codex did not start. See server-error.log.' }
            Start-Sleep -Milliseconds 200
        }
    }
    $result = & $tailscale serve --bg --yes --https 8443 --set-path / http://127.0.0.1:4500 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Tailscale Serve: $result" }
}
function Disable-Connection {
    $server = Get-Server
    if ($server) {
        Stop-Process -Id $server.ProcessId -ErrorAction Stop
        Wait-Process -Id $server.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    }
}
function Get-ConnectionStatus {
    $server = Get-Server
    if (!$server) { return 'Off' }
    $ts = & $tailscale status --json 2>$null | ConvertFrom-Json
    if ($ts.BackendState -ne 'Running') { return 'Tailscale offline' }
    $serve = & $tailscale serve status --json 2>$null | ConvertFrom-Json
    $mapped = $false
    foreach ($site in $serve.Web.PSObject.Properties) {
        if ($site.Name -like '*:8443' -and $site.Value.Handlers.'/'.Proxy -eq 'http://127.0.0.1:4500') { $mapped = $true }
    }
    if (!$mapped) { return 'Secure endpoint unavailable' }
    $connections = @(Get-NetTCPConnection -LocalPort 4500 -State Established -ErrorAction SilentlyContinue)
    if ($connections.Count) { return 'Client connection active' }
    return 'Ready - waiting for HackGpt'
}
if ($SmokeTest) {
    [pscustomobject]@{Status=(Get-ConnectionStatus); Endpoint=$endpoint; TokenExists=(Test-Path -LiteralPath $tokenFile)} | ConvertTo-Json
    exit
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$mutex = [Threading.Mutex]::new($false, 'Local\HackGptCodexTray')
if (!$mutex.WaitOne(0)) { $mutex.Dispose(); exit }
[Windows.Forms.Application]::EnableVisualStyles()
$tray = [Windows.Forms.NotifyIcon]::new()
$menu = [Windows.Forms.ContextMenuStrip]::new()
$statusItem = $menu.Items.Add('Checking connection...')
$statusItem.Enabled = $false
$toggle = $menu.Items.Add('Turn connection off')
[void]$menu.Items.Add('-')
$copyUrl = $menu.Items.Add('Copy server URL')
$copyToken = $menu.Items.Add('Copy connection token')
$copyProject = $menu.Items.Add('Copy project folder')
$logs = $menu.Items.Add('Open logs')
[void]$menu.Items.Add('-')
$exitItem = $menu.Items.Add('Exit tray (keep connection running)')
$tray.ContextMenuStrip = $menu
$tray.Icon = [Drawing.SystemIcons]::Information
$tray.Text = 'HackGpt Codex'
$tray.Visible = $true
function Show-Error($failure) {
    $tray.ShowBalloonTip(7000, 'HackGpt connection', [string]$failure, [Windows.Forms.ToolTipIcon]::Error)
    Add-Content -LiteralPath (Join-Path $appDir 'tray-error.log') -Value "$(Get-Date -Format o) $failure"
}
function Update-Status {
    try {
        $state = Get-ConnectionStatus
        $statusItem.Text = $state
        $tray.Text = "HackGpt: $state"
        $toggle.Text = if ($state -eq 'Off') { 'Turn connection on' } else { 'Turn connection off' }
        $tray.Icon = if ($state -eq 'Off') { [Drawing.SystemIcons]::Application } elseif ($state -eq 'Client connection active') { [Drawing.SystemIcons]::Shield } elseif ($state -like 'Ready*') { [Drawing.SystemIcons]::Information } else { [Drawing.SystemIcons]::Warning }
    } catch { $statusItem.Text = $_.Exception.Message; $tray.Icon = [Drawing.SystemIcons]::Error }
}
function Toggle-Connection {
    $toggle.Enabled = $false
    try { if (Get-Server) { Disable-Connection } else { Enable-Connection } } catch { Show-Error $_.Exception.Message }
    finally { $toggle.Enabled = $true; Update-Status }
}
$toggle.add_Click({ Toggle-Connection })
$tray.add_DoubleClick({ Toggle-Connection })
$copyUrl.add_Click({ [Windows.Forms.Clipboard]::SetText($endpoint) })
$copyToken.add_Click({ try { [Windows.Forms.Clipboard]::SetText([IO.File]::ReadAllText($tokenFile).Trim()) } catch { Show-Error $_.Exception.Message } })
$copyProject.add_Click({ [Windows.Forms.Clipboard]::SetText($project) })
$logs.add_Click({ Start-Process explorer.exe -ArgumentList ('"' + $appDir + '"') })
$exitItem.add_Click({ [Windows.Forms.Application]::ExitThread() })
$timer = [Windows.Forms.Timer]::new()
$timer.Interval = 5000
$timer.add_Tick({ Update-Status })
try {
    try { Enable-Connection } catch { Show-Error $_.Exception.Message }
    Update-Status
    $timer.Start()
    $tray.ShowBalloonTip(4000, 'HackGpt Codex', $statusItem.Text + '. Right-click for controls; double-click to toggle.', [Windows.Forms.ToolTipIcon]::Info)
    [Windows.Forms.Application]::Run()
} finally {
    $timer.Stop(); $timer.Dispose(); $tray.Visible = $false; $tray.Dispose(); $menu.Dispose()
    $mutex.ReleaseMutex(); $mutex.Dispose()
}
