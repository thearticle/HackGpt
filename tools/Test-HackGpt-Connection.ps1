param([string]$Project = 'C:\Users\Ojan\Documents\ChatGPT\android llm')
$ErrorActionPreference = 'Stop'
$ws = [Net.WebSockets.ClientWebSocket]::new()
$ws.Options.SetRequestHeader('Authorization', 'Bearer ' + [IO.File]::ReadAllText((Join-Path $env:LOCALAPPDATA 'HackGpt-codex-remote-token')).Trim())
$cts = [Threading.CancellationTokenSource]::new(45000)
$script:rpcId = 0
function Invoke-Rpc($method, $params) {
    $script:rpcId++
    $id = $script:rpcId
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{id=$id;method=$method;params=$params} | ConvertTo-Json -Depth 10 -Compress))
    $ws.SendAsync([ArraySegment[byte]]::new($bytes), [Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult() | Out-Null
    do {
        $stream = [IO.MemoryStream]::new()
        try {
            $buffer = New-Object byte[] 65536
            do {
                $received = $ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cts.Token).GetAwaiter().GetResult()
                if ($received.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { throw 'Server closed the connection.' }
                $stream.Write($buffer, 0, $received.Count)
            } until ($received.EndOfMessage)
            $reply = [Text.Encoding]::UTF8.GetString($stream.ToArray()) | ConvertFrom-Json
        } finally { $stream.Dispose() }
    } until ($reply.id -eq $id)
    if ($reply.error) { throw ($reply.error | ConvertTo-Json -Compress -Depth 5) }
    return $reply.result
}
try {
    $ws.ConnectAsync([Uri]'wss://desktop-5inlh0t.tail3c5fd0.ts.net:8443/', $cts.Token).GetAwaiter().GetResult() | Out-Null
    $null = Invoke-Rpc 'initialize' @{clientInfo=@{name='hackgpt_diagnostic';version='1.0'}}
    $bytes = [Text.Encoding]::UTF8.GetBytes('{"method":"initialized"}')
    $ws.SendAsync([ArraySegment[byte]]::new($bytes), [Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult() | Out-Null
    $default = Invoke-Rpc 'thread/list' @{cwd=$Project;limit=100}
    $explicit = Invoke-Rpc 'thread/list' @{cwd=$Project;limit=100;sourceKinds=@('cli','vscode','appServer','exec','unknown')}
    [pscustomobject]@{DefaultThreadCount=@($default.data).Count;ExplicitThreadCount=@($explicit.data).Count;Project=$Project} | ConvertTo-Json
    $all = Invoke-Rpc 'thread/list' @{limit=100;sourceKinds=@('cli','vscode','appServer','exec','unknown')}
    $all.data | Group-Object cwd | Select-Object Count,Name | Format-Table -AutoSize
    $thread = $explicit.data | Select-Object -First 1
    if ($thread) {
        $page = Invoke-Rpc 'thread/turns/list' @{threadId=$thread.id;limit=5;sortDirection='desc';itemsView='full'}
        [pscustomobject]@{HistoryThread=$thread.id;PagedTurns=@($page.data).Count;OlderHistoryAvailable=[bool]$page.nextCursor} | ConvertTo-Json
    }
} finally { $ws.Dispose(); $cts.Dispose() }
