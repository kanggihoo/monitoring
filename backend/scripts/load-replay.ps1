# 마켓 리플레이 시세 적재/검증/롤백 편의 스크립트 (PowerShell)
# 사용법:
#   .\scripts\load-replay.ps1 local         # 로컬 DB 적재 (ssafydb, mode=test)
#   .\scripts\load-replay.ps1 dev           # 개발 서버 DB 적재 (SSH 터널 자동, app_dev, mode=test)
#   .\scripts\load-replay.ps1 prod          # 운영 서버 DB 적재 (SSH 터널 자동, app_prod, mode=production)
#   .\scripts\load-replay.ps1 verify        # DB 접속 없이 원본 데이터 검증 및 행 수 계산
#   .\scripts\load-replay.ps1 rollback      # 로컬 DB 리플레이 데이터 삭제
#   .\scripts\load-replay.ps1 rollback dev  # 개발 서버 DB 리플레이 데이터 삭제
#   .\scripts\load-replay.ps1 rollback prod # 운영 서버 DB 리플레이 데이터 삭제
#
#   -Season N 으로 시즌을 고릅니다. 생략하면 **시즌 1 과 2 를 차례로** 처리합니다
#   (로컬에서 두 시즌을 한 번에 채우고 한 번에 되돌리는 것이 기본 쓰임이기 때문입니다).
#   시즌 1 은 data/replay, 시즌 2 는 data/replay-s2 를 읽습니다.
#   예) .\scripts\load-replay.ps1 local -Season 2
#
#   -StartDate YYYY-MM-DD 를 붙이면 1일차 개장일을 그 날짜로 옮겨 적재합니다 (이후 연속 일자).
#   생략하면 DB 의 현재 편성을 그대로 씁니다 (시즌 1 은 V18, 시즌 2 는 로더의 기본 개장일).
#   두 시즌을 함께 적재할 때는 쓸 수 없습니다 — 시즌마다 개장일이 다르므로 -Season 과 함께 씁니다.
#   예) .\scripts\load-replay.ps1 prod -Season 1 -StartDate 2026-09-15

param (
    [Parameter(Position=0)]
    [ValidateSet("local", "test", "dev", "prod", "production", "verify", "check", "rollback", "unload", "rollback-dev", "rollback-prod")]
    [string]$Command = "local",

    [Parameter(Position=1)]
    [ValidateSet("local", "dev", "prod", "")]
    [string]$Target = "",

    # 형식·과거 날짜 검사는 로더가 한다 — 한 곳에만 둔다. 여기서는 빈 값만 구분한다.
    [Parameter()]
    [string]$StartDate = "",

    # 0 이면 미지정. 값 검사는 로더가 한다 — 한 곳에만 둔다.
    [Parameter()]
    [ValidateSet(0, 1, 2)]
    [int]$Season = 0
)

$ErrorActionPreference = "Stop"
$env:PYTHONUTF8 = "1"

# -Season 을 생략하면 두 시즌을 차례로 처리한다.
$SeasonList = if ($Season) { @($Season) } else { @(1, 2) }

# 개장일은 시즌마다 다르므로 한 시즌을 지목했을 때만 받는다. 조용히 양쪽에 같은 날짜를 넣으면
# 두 시즌이 같은 날 개장한다.
if ($StartDate -and $SeasonList.Count -gt 1) {
    Write-Error "[오류] -StartDate 는 -Season 과 함께 씁니다. 시즌마다 개장일이 다릅니다."
    exit 1
}

# 로더에 그대로 넘길 인자. 비어 있으면 로더가 DB 의 현재 편성을 그대로 쓴다.
$LoadArgs = @()
if ($StartDate) { $LoadArgs = @("--start-date", $StartDate) }

# 현재 스크립트 위치 및 경로 설정
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Split-Path -Parent $ScriptDir
$LoaderDir = Join-Path $ScriptDir "replay-loader"
$BackendEnv = Join-Path $BackendDir ".env"
$LoaderEnv = Join-Path $LoaderDir ".env"

# uv 설치 여부 확인
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Write-Error "[오류] 'uv' 명령어를 찾을 수 없습니다. uv를 설치해주세요: https://docs.astral.sh/uv/"
    exit 1
}

# TCP 포트 오픈 여부 확인 헬퍼
function Test-PortOpen([string]$hostAddress, [int]$port) {
    try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $iar = $tcpClient.BeginConnect($hostAddress, $port, $null, $null)
        $success = $iar.AsyncWaitHandle.WaitOne(500, $false)
        if ($success) {
            $tcpClient.EndConnect($iar)
            $tcpClient.Close()
            return $true
        }
        $tcpClient.Close()
        return $false
    } catch {
        return $false
    }
}

# 로컬 DB 기본 환경변수 로드
function Set-LocalEnv {
    if (-not (Test-Path $LoaderEnv) -and (Test-Path $BackendEnv)) {
        Get-Content $BackendEnv | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $parts = $line.Split("=", 2)
                $k = $parts[0].Trim()
                $v = $parts[1].Trim()
                if ($k -in @("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")) {
                    if (-not [System.Environment]::GetEnvironmentVariable($k)) {
                        [System.Environment]::SetEnvironmentVariable($k, $v)
                    }
                }
            }
        }
    }
}

# 원격 서버 SSH 터널링 및 실행 래퍼
function Invoke-RemoteAction {
    param (
        [string]$TargetDb,
        [string]$ActionType, # "load" or "unload"
        [string]$Mode = "test"
    )

    $localPort = 55432
    $containerIp = "172.19.0.2"
    $remoteHost = "ssafy"
    $tunnelStarted = $false
    $sshProcess = $null

    if (-not (Test-PortOpen "127.0.0.1" $localPort)) {
        Write-Host "==> [SSH 터널] $remoteHost 서버와 터널 연결 시작 (127.0.0.1:$localPort -> ${containerIp}:5432)..." -ForegroundColor Cyan
        $sshProcess = Start-Process ssh -ArgumentList "-N", "-L", "${localPort}:${containerIp}:5432", $remoteHost -PassThru -WindowStyle Hidden
        $tunnelStarted = $true

        $ready = $false
        for ($i = 0; $i -lt 15; $i++) {
            Start-Sleep -Milliseconds 400
            if (Test-PortOpen "127.0.0.1" $localPort) {
                $ready = $true
                break
            }
        }
        if (-not $ready) {
            if ($sshProcess -and -not $sshProcess.HasExited) {
                Stop-Process -Id $sshProcess.Id -Force -ErrorAction SilentlyContinue
            }
            Write-Error "[오류] SSH 터널 연결에 실패했습니다. 'ssh $remoteHost' 접속이 정상인지 확인해주세요."
            exit 1
        }
        Write-Host "==> [SSH 터널] 포트 $localPort 연결 성공." -ForegroundColor DarkCyan
    } else {
        Write-Host "==> [SSH 터널] 기존 활성 터널(포트 $localPort)을 재사용합니다." -ForegroundColor Cyan
    }

    try {
        $env:POSTGRES_HOST = "127.0.0.1"
        $env:POSTGRES_PORT = "$localPort"
        $env:POSTGRES_DB   = $TargetDb
        $env:POSTGRES_USER = "a605"
        $env:POSTGRES_PASSWORD = if ($env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD } else { "JxBaEABG0GMM33y33RiaRUUu9iWF" }

        foreach ($s in $SeasonList) {
            if ($ActionType -eq "load") {
                Write-Host "==> [마켓 리플레이] $TargetDb 시즌 $s 적재 시작 (mode=$Mode)..." -ForegroundColor Green
                uv run --directory "$LoaderDir" load_replay_data.py --mode $Mode --season $s @LoadArgs
            } else {
                Write-Host "==> [마켓 리플레이] $TargetDb 시즌 $s 롤백 시작..." -ForegroundColor Yellow
                uv run --directory "$LoaderDir" unload_replay_data.py --season $s
            }
        }
    }
    finally {
        if ($tunnelStarted -and $sshProcess -and -not $sshProcess.HasExited) {
            Write-Host "==> [SSH 터널] 임시 터널 프로세스를 정상 종료합니다." -ForegroundColor DarkGray
            Stop-Process -Id $sshProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

# 명령어 분기
switch ($Command) {
    { $_ -in "local", "test" } {
        Set-LocalEnv
        foreach ($s in $SeasonList) {
            Write-Host "==> [마켓 리플레이] 로컬 DB(ssafydb) 시즌 $s 적재 시작 (mode=test)..." -ForegroundColor Cyan
            uv run --directory "$LoaderDir" load_replay_data.py --mode test --season $s @LoadArgs
        }
    }
    "dev" {
        Invoke-RemoteAction -TargetDb "app_dev" -ActionType "load" -Mode "test"
    }
    { $_ -in "prod", "production" } {
        Write-Host "⚠️  [경고] 운영 서버 DB(app_prod)에 데이터를 적재합니다!" -ForegroundColor Yellow
        # 개장일은 눈으로 확인하고 yes 를 치게 한다. 잘못 넣으면 시즌이 엉뚱한 날 시작한다.
        if ($StartDate) {
            Write-Host "    개장일: $StartDate 부터 연속 일자 (1일차 11:05 KST, 브리핑 11:00)" -ForegroundColor Yellow
        } else {
            Write-Host "    개장일: DB 의 현재 편성을 그대로 사용 (시즌 1 은 V18)" -ForegroundColor DarkGray
        }
        $ans = Read-Host "정말 운영 DB에 데이터를 적재하시겠습니까? (yes/no)"
        if ($ans -ne "yes") {
            Write-Warning "운영 적재 작업이 취소되었습니다."
            exit 0
        }
        Invoke-RemoteAction -TargetDb "app_prod" -ActionType "load" -Mode "production"
    }
    { $_ -in "verify", "check" } {
        foreach ($s in $SeasonList) {
            Write-Host "==> [마켓 리플레이] 시즌 $s 데이터 검증 및 자기시험 실행 (--verify)..." -ForegroundColor Cyan
            uv run --directory "$LoaderDir" load_replay_data.py --verify --season $s
        }
    }
    "rollback-dev" {
        Invoke-RemoteAction -TargetDb "app_dev" -ActionType "unload"
    }
    "rollback-prod" {
        Write-Host "⚠️  [경고] 운영 서버 DB(app_prod) 데이터를 롤백(삭제)합니다!" -ForegroundColor Red
        $ans = Read-Host "정말 운영 DB 데이터를 롤백하시겠습니까? (yes/no)"
        if ($ans -ne "yes") {
            Write-Warning "운영 롤백 작업이 취소되었습니다."
            exit 0
        }
        Invoke-RemoteAction -TargetDb "app_prod" -ActionType "unload"
    }
    { $_ -in "rollback", "unload" } {
        if ($Target -eq "dev") {
            Invoke-RemoteAction -TargetDb "app_dev" -ActionType "unload"
        } elseif ($Target -eq "prod") {
            Write-Host "⚠️  [경고] 운영 서버 DB(app_prod) 데이터를 롤백(삭제)합니다!" -ForegroundColor Red
            $ans = Read-Host "정말 운영 DB 데이터를 롤백하시겠습니까? (yes/no)"
            if ($ans -ne "yes") {
                Write-Warning "운영 롤백 작업이 취소되었습니다."
                exit 0
            }
            Invoke-RemoteAction -TargetDb "app_prod" -ActionType "unload"
        } else {
            Set-LocalEnv
            foreach ($s in $SeasonList) {
                Write-Host "==> [마켓 리플레이] 로컬 DB 시즌 $s 데이터 롤백 실행..." -ForegroundColor Yellow
                uv run --directory "$LoaderDir" unload_replay_data.py --season $s
            }
        }
    }
}
