# Try Codex on PC from HackGpt

Tap the same spot in the main chat area seven times to open the Codex panel. Tap outside the panel to close it. HackGpt uses the Codex App Server protocol. Threads come from the App Server's Codex environment on your PC and are filtered by the project path you enter.

1. On the PC, install or update the Codex CLI and sign in. Connect the PC and phone to the same private Tailscale network. Tailscale Serve needs HTTPS enabled for that network.
2. In Windows PowerShell or PowerShell 7 on the PC, create the token file if it is missing or empty, then start Codex on loopback. Keep this window open during the test.

   ```powershell
   $codexTokenFile = Join-Path $env:LOCALAPPDATA 'HackGpt-codex-remote-token'
   if (-not (Test-Path -LiteralPath $codexTokenFile) -or
       (Get-Item -LiteralPath $codexTokenFile).Length -eq 0) {
       $bytes = New-Object byte[] 32
       $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
       try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
       [IO.File]::WriteAllText($codexTokenFile, [Convert]::ToBase64String($bytes))
   }
   codex app-server --listen ws://127.0.0.1:4500 --ws-auth capability-token --ws-token-file $codexTokenFile
   ```

3. In a second PowerShell window, run `tailscale serve 4500` and leave it running. Use the HTTPS address it prints, with `wss://` in place of `https://`. Use Serve for private access.
4. In HackGpt, open the Codex connection settings from the toolbar gear beside Appearance, or tap the chat area seven times. Enter the URL and the token from `Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'HackGpt-codex-remote-token')`. For the project folder, enter the full path to the repository on the PC. Run `(Get-Location).Path` from the repository to get that path. HackGpt saves the submitted details on this device using Android Keystore encryption. Leave the token field empty to reuse the saved token for the same URL.
5. Tap **Connect**, then use the circled folder beside Appearance to choose a project thread. The selected thread name appears above the message field. Use the circled **+** to start a thread and the circular arrows to refresh the list. Tap the square in the circle above the message field to interrupt a running turn.
6. Tap the pulsing **X** next to Appearance to return to your regular chat. Tap the chat seven times to reopen the connected Codex thread. Use the power icon in the top-right toolbar to disconnect.

To end the test, disconnect in HackGpt and interrupt both PowerShell processes with Ctrl+C. The Windows PC must remain awake while connected.

[Codex App Server protocol](https://learn.chatgpt.com/docs/app-server)

## Antigravity model and quota display

Run the metadata helper on the PC alongside the Antigravity-backed Codex server.
Set `HACKGPT_ANTIGRAVITY_KEY` in the helper process environment and pass the
same capability-token file used by the Codex server:

```powershell
node preview/antigravity-metadata.cjs $codexTokenFile
```

Use private Tailscale Serve to map the Antigravity connection path plus
`/metadata` to `http://127.0.0.1:4502`. For example:

```powershell
tailscale serve --bg --https 8443 --set-path /antigravity/metadata http://127.0.0.1:4502
```

The helper sends only model metadata and per-model quota for the currently
active account. Antigravity Tools may choose another account for a request.
