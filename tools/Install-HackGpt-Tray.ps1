$ErrorActionPreference = 'Stop'
$destination = Join-Path $env:LOCALAPPDATA 'HackGpt-Tray'
[void][IO.Directory]::CreateDirectory($destination)
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'HackGpt-Tray.ps1') -Destination (Join-Path $destination 'HackGpt-Tray.ps1') -Force
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'HackGpt Connection.lnk'))
$shortcut.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "' + (Join-Path $destination 'HackGpt-Tray.ps1') + '"'
$shortcut.WorkingDirectory = $destination
$shortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,17"
$shortcut.Description = 'Start HackGpt Codex connection and system tray controls'
$shortcut.Save()
Start-Process -FilePath $shortcut.TargetPath -ArgumentList $shortcut.Arguments -WindowStyle Hidden
Write-Output 'Installed and started HackGpt Connection. Desktop shortcut created.'
