# 최초 관리자 승격 PowerShell 스크립트 (Windows 전용)
#
# 사용법:
#   .\backend\scripts\promote-admin.ps1          # 최근 가입자 목록 조회
#   .\backend\scripts\promote-admin.ps1 42       # ID 42 유저를 ADMIN으로 승격
#
# SQL 은 -c 인자가 아니라 표준입력(-f -)으로 넘긴다. -c 에 넣으면 PowerShell -> docker ->
# sh -> psql 로 인용부호가 네 겹 중첩되어 첫 공백에서 잘린다 (psql 이 "SELECT" 한 단어만 받아
# 빈 표가 나왔다). 같은 이유로 sh 에 주는 명령은 PowerShell 이 건드리지 않도록 단일 인용부호로
# 감싸고, $POSTGRES_USER 는 컨테이너 안의 sh 가 풀게 둔다. promote-admin.sh 와 같은 방식이다.

param (
    [Parameter(Position = 0)]
    [string]$TargetId = ""
)

$ErrorActionPreference = "Stop"
$RecentLimit = 20
$BackendDir = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $BackendDir "compose.yml"

# 식별자는 psql 변수 :'id' 로만 들어간다. 문자열에 직접 끼워 넣지 않는다.
$Psql = 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -P pager=off -v id="${TARGET_ID:-}" -f -'

if ([string]::IsNullOrWhiteSpace($TargetId)) {
    Write-Host "최근 가입자 $RecentLimit 명입니다. 대상의 식별자를 골라 다시 실행하세요."
    Write-Host "  .\backend\scripts\promote-admin.ps1 <식별자>`n"

    # 테스트용 사용자는 제외한다. 시드 인원이 늘면 최근 목록이 전부 그것들로 채워져
    # 정작 승격할 대상을 찾을 수 없다.
    $listQuery = @"
SELECT u.id, u.nickname, u.email, u.role, u.created_at
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM test_users t WHERE t.user_id = u.id)
ORDER BY u.created_at DESC, u.id DESC
LIMIT $RecentLimit;
"@
    $listQuery | docker compose -f $ComposeFile exec -T postgres sh -c $Psql
    exit 0
}

if (-not ($TargetId -match '^[0-9]+$')) {
    Write-Error "식별자는 숫자여야 합니다: $TargetId"
    exit 1
}

$updateQuery = "UPDATE users SET role = 'ADMIN', updated_at = now() WHERE id = :'id'::bigint;"
$result = $updateQuery |
    docker compose -f $ComposeFile exec -T -e "TARGET_ID=$TargetId" postgres sh -c $Psql |
    Out-String

Write-Host $result

if ($result -match "UPDATE 0") {
    Write-Error "해당 식별자의 User 가 없습니다. 인자 없이 실행해 목록을 먼저 확인하세요."
    exit 1
}

Write-Host "승격 완료. 권한은 세션에 담기므로 대상자가 다시 로그인해야 반영됩니다." -ForegroundColor Green
