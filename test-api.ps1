<#
    test-api.ps1 - manual smoke tests for the Ledger API

    Usage:
        .\test-api.ps1              # run all scenarios
        .\test-api.ps1 -Only 2      # run only scenario 2

    Requires the app to be running (mvn spring-boot:run).
    IDs are looked up from the database by account number / username,
    so this keeps working after the sequences advance.
#>

param(
    [int]$Only = 0,
    [string]$BaseUrl = "http://localhost:8081",
    [string]$DbUser  = "ledger_app",
    [string]$DbName  = "ledger"
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Look up IDs from the database instead of hardcoding them
# ---------------------------------------------------------------------------

function Get-DbValue([string]$sql) {
    # -t = tuples only (no header), -A = unaligned (no padding)
    $result = psql -U $DbUser -d $DbName -t -A -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed. Is DB_PASSWORD set (or PGPASSWORD)?" }
    return $result.Trim()
}

Write-Host "Resolving IDs from database..." -ForegroundColor DarkGray

$userId  = Get-DbValue "SELECT id FROM users WHERE username = 'arpan';"
$acc1Id  = Get-DbValue "SELECT id FROM accounts WHERE account_number = 'ACC001';"
$acc2Id  = Get-DbValue "SELECT id FROM accounts WHERE account_number = 'ACC002';"

if (-not $userId -or -not $acc1Id -or -not $acc2Id) {
    throw "Could not resolve user/account IDs. Check seed data exists."
}

Write-Host "  user=$userId  ACC001=$acc1Id  ACC002=$acc2Id`n" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Show-Balances([string]$label) {
    $rows = psql -U $DbUser -d $DbName -t -A -F " | " -c @"
SELECT account_number, balance_minor FROM accounts ORDER BY account_number;
"@
    Write-Host "  ${label}: $($rows -join '   ')" -ForegroundColor DarkGray
}

function Show-Counts() {
    $t = Get-DbValue "SELECT count(*) FROM transfers;"
    $e = Get-DbValue "SELECT count(*) FROM ledger_entries;"
    Write-Host "  rows: transfers=$t ledger_entries=$e" -ForegroundColor DarkGray
}

function Invoke-Transfer($name, $payload, $expectedStatus) {
    Write-Host "`n=== $name ===" -ForegroundColor Cyan

    $json = $payload | ConvertTo-Json -Compress
    Write-Host "  request: $json" -ForegroundColor DarkGray

    $status = 200
    $body   = $null

    try {
        $body = Invoke-RestMethod -Uri "$BaseUrl/api/transfers" `
                                  -Method Post `
                                  -Body $json `
                                  -ContentType "application/json"
    }
    catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { Write-Host "  NO RESPONSE - is the app running?" -ForegroundColor Red; return }

        $status = [int]$response.StatusCode
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $body   = $reader.ReadToEnd()
    }

    $ok = ($status -eq $expectedStatus)
    $colour = if ($ok) { "Green" } else { "Red" }
    $verdict = if ($ok) { "PASS" } else { "FAIL (expected $expectedStatus)" }

    Write-Host "  status:  $status  [$verdict]" -ForegroundColor $colour
    Write-Host "  body:    $($body | ConvertTo-Json -Compress -Depth 5)"
}

# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------

Show-Balances "before"
Show-Counts

# 1. Happy path - should succeed
if ($Only -eq 0 -or $Only -eq 1) {
    Invoke-Transfer "1. Valid transfer (250.00)" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 25000
        description       = "Smoke test transfer"
        initiatedByUserId = [long]$userId
    } 200
}

# 2. Overdraft - application + DB constraint should both refuse
if ($Only -eq 0 -or $Only -eq 2) {
    Invoke-Transfer "2. Overdraft (10,000.00 from a smaller balance)" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 1000000
        description       = "Overdraft test"
        initiatedByUserId = [long]$userId
    } 400
}

# 3. Self-transfer - rejected before any DB work
if ($Only -eq 0 -or $Only -eq 3) {
    Invoke-Transfer "3. Self-transfer" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc1Id
        amountMinor       = 1000
        description       = "Self transfer test"
        initiatedByUserId = [long]$userId
    } 400
}

# 4. Zero amount
if ($Only -eq 0 -or $Only -eq 4) {
    Invoke-Transfer "4. Zero amount" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 0
        description       = "Zero test"
        initiatedByUserId = [long]$userId
    } 400
}

# 5. Negative amount
if ($Only -eq 0 -or $Only -eq 5) {
    Invoke-Transfer "5. Negative amount" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = -5000
        description       = "Negative test"
        initiatedByUserId = [long]$userId
    } 400
}

# 6. Nonexistent destination account
if ($Only -eq 0 -or $Only -eq 6) {
    Invoke-Transfer "6. Nonexistent destination account" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = 999999
        amountMinor       = 1000
        description       = "Missing account test"
        initiatedByUserId = [long]$userId
    } 400
}

# 7. Nonexistent user
if ($Only -eq 0 -or $Only -eq 7) {
    Invoke-Transfer "7. Nonexistent initiating user" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 1000
        description       = "Missing user test"
        initiatedByUserId = 999999
    } 400
}

# ---------------------------------------------------------------------------
# Final state + invariant check
# ---------------------------------------------------------------------------

Write-Host "`n=== Final state ===" -ForegroundColor Cyan
Show-Balances "after"
Show-Counts

Write-Host "`n=== Invariant: every transfer's ledger entries sum to zero ===" -ForegroundColor Cyan
$unbalanced = Get-DbValue @"
SELECT count(*) FROM (
    SELECT transfer_id FROM ledger_entries
    GROUP BY transfer_id HAVING SUM(amount_minor) <> 0
) t;
"@
if ($unbalanced -eq "0") {
    Write-Host "  PASS - all transfers balance" -ForegroundColor Green
} else {
    Write-Host "  FAIL - $unbalanced transfer(s) do not balance" -ForegroundColor Red
}

Write-Host "`n=== Reconciliation: stored balance vs ledger sum ===" -ForegroundColor Cyan
$drift = psql -U $DbUser -d $DbName -t -A -F " | " -c @"
SELECT a.account_number,
       a.balance_minor AS stored,
       COALESCE(SUM(e.amount_minor), 0) AS derived,
       a.balance_minor - COALESCE(SUM(e.amount_minor), 0) AS drift
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.account_number, a.balance_minor
ORDER BY a.account_number;
"@
$drift | ForEach-Object { Write-Host "  $_" }
Write-Host "`n  (drift is non-zero for accounts seeded with an opening balance" -ForegroundColor DarkGray
Write-Host "   that had no matching ledger entry - expected until seeding writes entries)" -ForegroundColor DarkGray