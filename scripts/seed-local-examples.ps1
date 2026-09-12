#requires -Version 7.2
<#
Adds five purchasable local examples through ordinary administrator HTTP endpoints.
Run after run-local.ps1 -Action Start. Existing events, orders and users are retained.
Repeated runs skip an exact title that already has a session; no inventory is reset.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^1[3-9][0-9]{9}$')][string]$AdminPhone = '13900000000'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Codex\ticketing'
if ([IO.Path]::GetFullPath($PSScriptRoot) -ine "$taskRoot\scripts") { throw 'Unexpected project root' }
$taskUrl = 'http://127.0.0.1:18082'
$taskPowerShell = (Get-Process -Id $PID).Path
$taskToken = $null
$taskCode = $null
$taskCodeOutput = $null
$taskMutex = [Threading.Mutex]::new($false, 'Local\TicketingSeedLocalExamples18082')
$taskAcquired = $false

function Invoke-ExampleApi {
    param([string]$Method, [string]$Path, [AllowNull()][hashtable]$Body, [switch]$Anonymous)
    if ($Path -notmatch '^/v1/[A-Za-z0-9/?=&-]+$') { throw 'Unexpected API path' }
    $arguments = @{
        Uri = "$taskUrl$Path"; Method = $Method; TimeoutSec = 20
        SkipHttpErrorCheck = $true; MaximumRedirection = 0
    }
    if (-not $Anonymous) { $arguments.Headers = @{ Authorization = "Bearer $taskToken" } }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress -Depth 6))
    }
    try {
        $reply = Invoke-WebRequest @arguments
        $content = $reply.Content | ConvertFrom-Json -AsHashtable
    } catch {
        throw "Local API request failed: $Method $Path. Check that the local website is ready."
    }
    return @{ Status = [int]$reply.StatusCode; Body = $content; Headers = $reply.Headers }
}

function Require-ExampleSuccess {
    param([hashtable]$Reply, [string]$Action)
    if ($Reply.Status -notin @(200, 201)) {
        $errorCode = if ($Reply.Body.ContainsKey('code')) { [string]$Reply.Body.code } else { 'UNEXPECTED_RESPONSE' }
        throw "$Action failed: HTTP $($Reply.Status) / $errorCode"
    }
    return $Reply.Body
}

function Get-ExampleCatalogue {
    $all = [Collections.Generic.List[object]]::new()
    for ($page = 1; $page -le 1000; $page++) {
        $result = Require-ExampleSuccess (Invoke-ExampleApi GET "/v1/admin/events?page=$page&size=20") 'Read admin catalogue'
        foreach ($item in $result.items) { $all.Add($item) }
        if (-not $result.hasNext) { return ,$all }
    }
    throw 'Admin catalogue exceeded the supported pagination limit; nothing further was added.'
}

try {
    try { $taskAcquired = $taskMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $taskAcquired = $true }
    if (-not $taskAcquired) { throw 'Another example seed is running; wait for it to finish.' }

    $taskSent = $false
    $taskWaitedSeconds = 0
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $reply = Invoke-ExampleApi POST '/v1/auth/code' @{ phone = $AdminPhone } -Anonymous
        if ($reply.Status -eq 200) { $taskSent = $true; break }
        $limited = $reply.Status -eq 429 -and $reply.Body.code -in @('CODE_SEND_TOO_FREQUENT', 'CODE_SEND_RATE_LIMITED')
        if (-not $limited -or $attempt -eq 3) { $null = Require-ExampleSuccess $reply 'Send administrator code' }
        $retryAfter = [string](@($reply.Headers['Retry-After'])[0])
        if ($retryAfter -notmatch '^[0-9]{1,3}$') { throw 'Code rate limit did not provide a valid Retry-After.' }
        $seconds = [int]$retryAfter
        $taskWaitedSeconds += $seconds
        if ($seconds -lt 1 -or $seconds -gt 60 -or $taskWaitedSeconds -gt 120) { throw 'Code rate limit exceeded the bounded wait budget.' }
        Write-Host "Administrator code is rate limited; waiting $seconds seconds before retrying."
        Start-Sleep -Seconds $seconds
    }
    if (-not $taskSent) { throw 'Administrator code was not sent.' }

    # Capture the helper's output in memory: neither OTP nor token is sent to the console.
    $taskCodeOutput = (& $taskPowerShell -NoProfile -File "$taskRoot\scripts\run-local.ps1" -Action Code -Phone $AdminPhone 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $taskCodeOutput -notmatch 'Latest local code: ([0-9]{6})\b') {
        throw 'Could not obtain the new administrator code from run-local Code.'
    }
    $taskCode = $Matches[1]
    $login = Require-ExampleSuccess (Invoke-ExampleApi POST '/v1/auth/login' @{ phone = $AdminPhone; code = $taskCode } -Anonymous) 'Administrator login'
    $taskCode = $null
    $taskCodeOutput = $null
    $taskToken = [string]$login.token
    if ($taskToken -cnotmatch '^[a-f0-9]{64}$' -or $login.user.role -cne 'ADMIN') { throw 'The configured phone did not produce an administrator session.' }
    $login = $null
    $identity = Require-ExampleSuccess (Invoke-ExampleApi GET '/v1/auth/me') 'Check administrator identity'
    if ($identity.role -cne 'ADMIN') { throw 'Administrator identity check failed.' }

    $examples = @(
        @{ title = '星光慢调 · 周末室内音乐会'; days = 14; price = 19900; venue = '星河音乐厅'; detail = '弦乐与钢琴在夜色里相遇，为忙碌的一周留一段安静的旋律。' },
        @{ title = '光影之间 · 城市摄影展'; days = 18; price = 5900; venue = '白墙艺术空间'; detail = '沿着街角、窗边与雨后的路面，重新发现城市日常里的光。' },
        @{ title = '今晚有笑 · 轻喜剧开放麦'; days = 21; price = 9900; venue = '转角喜剧小馆'; detail = '五位创作者分享生活里的小尴尬与大笑点，轻松度过一个晚上。' },
        @{ title = '手心有光 · 陶艺手作体验'; days = 25; price = 12900; venue = '拾光陶艺工作室'; detail = '从一团泥开始，亲手塑形、修整与装饰，给生活留一件小作品。' },
        @{ title = '晚风来信 · 小剧场戏剧夜'; days = 30; price = 16900; venue = '梧桐小剧场'; detail = '一封迟来的信，把几位陌生人带进同一个温柔而意外的夜晚。' }
    )
    $catalogue = Get-ExampleCatalogue
    $results = [Collections.Generic.List[object]]::new()
    $now = [DateTimeOffset]::UtcNow
    $timeFormat = "yyyy-MM-dd'T'HH:mm:ss.fff'Z'"
    foreach ($example in $examples) {
        $existing = @($catalogue | Where-Object { [string]::Equals([string]$_.title, $example.title, [StringComparison]::Ordinal) })
        if ($existing.Count -gt 1) { throw "Multiple exact title matches found: $($example.title). No matching event was changed." }
        if ($existing.Count -eq 1 -and @($existing[0].sessions).Count -gt 0) {
            $session = @($existing[0].sessions)[0]
            $results.Add([ordered]@{
                action = 'SKIPPED'; title = $example.title; eventId = $existing[0].eventId
                sessionId = $session.sessionId; state = $session.state
                tierIds = @($session.ticketTiers | ForEach-Object { $_.tierId })
            })
            continue
        }
        $createdEvent = $existing.Count -eq 0
        $event = if ($createdEvent) {
            Require-ExampleSuccess (Invoke-ExampleApi POST '/v1/admin/events' @{
                title = $example.title; description = "$($example.detail)【本地演示】可体验购票、付款与订单流程；付款为模拟，无真实资金。"
                venueText = $example.venue
            }) 'Create example event'
        } else { $existing[0] }
        if ([string]$event.eventId -eq '49') { throw 'Protected browser-demo event will not be changed.' }
        $start = $now.AddDays($example.days)
        $session = Require-ExampleSuccess (Invoke-ExampleApi POST '/v1/admin/sessions' @{
            eventId = [string]$event.eventId; startAt = $start.ToString($timeFormat)
            bookingOpenAt = $now.AddMinutes(-1).ToString($timeFormat)
            bookingCloseAt = $start.AddHours(-2).ToString($timeFormat)
        }) 'Create example session'
        $tier = Require-ExampleSuccess (Invoke-ExampleApi POST '/v1/admin/ticket-tiers' @{
            sessionId = [string]$session.sessionId; name = '标准票'; areaLabel = '体验区'
            capacity = 50; unitPriceCent = $example.price
        }) 'Create example ticket tier'
        $published = Require-ExampleSuccess (Invoke-ExampleApi POST "/v1/admin/sessions/$($session.sessionId)/publish" @{}) 'Publish example session'
        if ($published.state -cne 'OPEN') { throw 'Example session did not become OPEN.' }
        $publicEvent = Require-ExampleSuccess (Invoke-ExampleApi GET "/v1/events/$($event.eventId)" -Anonymous) 'Verify public example event'
        $publicTiers = Require-ExampleSuccess (Invoke-ExampleApi GET "/v1/sessions/$($session.sessionId)/ticket-tiers" -Anonymous) 'Verify public example tier'
        $visibleTier = @($publicTiers.items | Where-Object { [string]$_.tierId -eq [string]$tier.tierId })
        if ($publicEvent.title -cne $example.title -or $publicTiers.state -cne 'OPEN' -or $visibleTier.Count -ne 1 -or
            $visibleTier[0].capacity -ne 50 -or $visibleTier[0].unitPriceCent -ne $example.price) {
            throw 'Published example did not match its public catalogue response.'
        }
        $results.Add([ordered]@{
            action = $(if ($createdEvent) { 'CREATED' } else { 'COMPLETED' }); title = $example.title
            eventId = $event.eventId; sessionId = $session.sessionId; tierIds = @($tier.tierId)
            state = $published.state; capacity = 50; priceYuan = $example.price / 100
            startAt = $session.startAt
        })
    }
    [ordered]@{
        baseUrl = $taskUrl; simulatedPayment = $true
        created = @($results | Where-Object { $_.action -eq 'CREATED' }).Count
        completed = @($results | Where-Object { $_.action -eq 'COMPLETED' }).Count
        skipped = @($results | Where-Object { $_.action -eq 'SKIPPED' }).Count
        events = $results.ToArray()
    } | ConvertTo-Json -Depth 6
} finally {
    if ($taskToken) {
        try { $null = Invoke-ExampleApi POST '/v1/auth/logout' } catch { Write-Warning 'The seed session could not be logged out; it will expire normally.' }
    }
    $taskToken = $null
    $taskCode = $null
    $taskCodeOutput = $null
    if ($taskAcquired) { $taskMutex.ReleaseMutex() }
    $taskMutex.Dispose()
}
