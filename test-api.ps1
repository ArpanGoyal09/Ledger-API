<#
    test-api.ps1 - manual smoke tests for the Ledger API

    Usage:
        .\test-api.ps1              # seed, then run all scenarios
        .\test-api.ps1 -NoSeed      # skip seeding (accounts already funded)
        .\test-api.ps1 -Only 3      # run only scenario 3

    Requires the app to be running (mvn spring-boot:run) and PGPASSWORD set.

    Seeding goes through POST /api/transfers/deposits rather than raw SQL, so
    every rupee in the system has a matching ledger entry and reconciliation
    returns balanced. Seeding with UPDATE would create money with no ledger
    backing, which is exactly the drift the reconcile endpoint exists to detect.
#>

param(
    [int]$Only = 0,
    [switch]$NoSeed,
    [string]$BaseUrl = "http://localhost:8081",
    [string]$DbUser  = "ledger_app",
    [string]$DbName  = "ledger"
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Get-DbValue([string]$sql) {
    $result = psql -U $DbUser -d $DbName -t -A -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed. Is PGPASSWORD set?" }
    return $result.Trim()
}

function Show-Balances([string]$label) {
    $rows = psql -U $DbUser -d $DbName -t -A -F " | " -c @"
SELECT account_number, account_type, balance_minor FROM accounts ORDER BY account_type, account_number;
"@
    Write-Host "  ${label}:" -ForegroundColor DarkGray
    $rows | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
}

function Invoke-Api($name, $path, $method, $payload, $expectedStatus) {
    Write-Host "`n=== $name ===" -ForegroundColor Cyan

    $status = 200
    $body   = $null
    $args   = @{
        Uri         = "$BaseUrl$path"
        Method      = $method
        ContentType = "application/json"
    }

    if ($null -ne $payload) {
        $json = $payload | ConvertTo-Json -Compress
        $args["Body"] = $json
        Write-Host "  request: $json" -ForegroundColor DarkGray
    } else {
        Write-Host "  request: $method $path" -ForegroundColor DarkGray
    }

    try {
        $body = Invoke-RestMethod @args
    }
    catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            Write-Host "  NO RESPONSE - is the app running on $BaseUrl ?" -ForegroundColor Red
            return $null
        }
        $status = [int]$response.StatusCode
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $body   = $reader.ReadToEnd()
    }

    $ok      = ($status -eq $expectedStatus)
    $colour  = if ($ok) { "Green" } else { "Red" }
    $verdict = if ($ok) { "PASS" } else { "FAIL (expected $expectedStatus)" }

    Write-Host "  status:  $status  [$verdict]" -ForegroundColor $colour
    Write-Host "  body:    $($body | ConvertTo-Json -Compress -Depth 5)"
    return $body
}

# ---------------------------------------------------------------------------
# Resolve IDs from the database rather than hardcoding them.
# Sequences guarantee uniqueness, not contiguity - ids are never assumed.
# ---------------------------------------------------------------------------

Write-Host "Resolving IDs from database..." -ForegroundColor DarkGray

$userId    = Get-DbValue "SELECT id FROM users WHERE username = 'arpan';"
$acc1Id    = Get-DbValue "SELECT id FROM accounts WHERE account_number = 'ACC001';"
$acc2Id    = Get-DbValue "SELECT id FROM accounts WHERE account_number = 'ACC002';"
$systemId  = Get-DbValue "SELECT id FROM accounts WHERE account_type = 'SYSTEM';"

if (-not $userId -or -not $acc1Id -or -not $acc2Id) {
    throw "Could not resolve user/account IDs. Seed users and accounts first."
}
if (-not $systemId) {
    throw "No SYSTEM account found. Did SystemAccountInitializer run?"
}

Write-Host "  user=$userId  ACC001=$acc1Id  ACC002=$acc2Id  SYSTEM=$systemId`n" -ForegroundColor DarkGray

Show-Balances "before"

# ---------------------------------------------------------------------------
# Seed through the deposit endpoint so every balance has ledger backing
# ---------------------------------------------------------------------------

if (-not $NoSeed) {
    Write-Host "`n--- Seeding via deposit endpoint ---" -ForegroundColor Yellow

    Invoke-Api "Seed: deposit 1000.00 into ACC001" "/api/transfers/deposits" "Post" @{
        toAccountId = [long]$acc1Id
        amountMinor = 100000
        description = "Opening deposit ACC001"
    } 200 | Out-Null

    Invoke-Api "Seed: deposit 500.00 into ACC002" "/api/transfers/deposits" "Post" @{
        toAccountId = [long]$acc2Id
        amountMinor = 50000
        description = "Opening deposit ACC002"
    } 200 | Out-Null
}

# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------

if ($Only -eq 0 -or $Only -eq 1) {
    Invoke-Api "1. Valid transfer (250.00)" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 25000
        description       = "Smoke test transfer"
        initiatedByUserId = [long]$userId
    } 200 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 2) {
    Invoke-Api "2. Overdraft rejected" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 99999900
        description       = "Overdraft test"
        initiatedByUserId = [long]$userId
    } 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 3) {
    Invoke-Api "3. Self-transfer rejected" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc1Id
        amountMinor       = 1000
        description       = "Self transfer test"
        initiatedByUserId = [long]$userId
    } 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 4) {
    Invoke-Api "4. Zero amount rejected" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 0
        description       = "Zero test"
        initiatedByUserId = [long]$userId
    } 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 5) {
    Invoke-Api "5. Negative amount rejected" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = -5000
        description       = "Negative test"
        initiatedByUserId = [long]$userId
    } 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 6) {
    Invoke-Api "6. Unknown destination account rejected" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = 999999
        amountMinor       = 1000
        description       = "Missing account test"
        initiatedByUserId = [long]$userId
    } 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 7) {
    Invoke-Api "7. Unknown initiating user rejected" "/api/transfers" "Post" @{
        fromAccountId     = [long]$acc1Id
        toAccountId       = [long]$acc2Id
        amountMinor       = 1000
        description       = "Missing user test"
        initiatedByUserId = 999999
    } 400 | Out-Null
}

# SECURITY: the system account is exempt from the non-negative balance rule,
# so allowing it as a transfer source would let any caller create money.
# The public transfer endpoint must refuse it.
if ($Only -eq 0 -or $Only -eq 8) {
    Invoke-Api "8. SECURITY - system account rejected as transfer source" "/api/transfers" "Post" @{
        fromAccountId     = [long]$systemId
        toAccountId       = [long]$acc1Id
        amountMinor       = 100000
        description       = "Attempt to mint money"
        initiatedByUserId = [long]$userId
    } 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 9) {
    Invoke-Api "9. Deposit accepted" "/api/transfers/deposits" "Post" @{
        toAccountId = [long]$acc2Id
        amountMinor = 10000
        description = "Additional deposit"
    } 200 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 10) {
    Invoke-Api "10. Malformed body rejected" "/api/transfers" "Post" $null 400 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 11) {
    Invoke-Api "11. Account details" "/api/accounts/$acc1Id" "Get" $null 200 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 12) {
    Invoke-Api "12. Transaction history" "/api/accounts/$acc1Id/entries" "Get" $null 200 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 13) {
    Invoke-Api "13. Reconciliation" "/api/accounts/$acc1Id/reconcile" "Get" $null 200 | Out-Null
}

if ($Only -eq 0 -or $Only -eq 14) {
    Invoke-Api "14. Unknown account returns error" "/api/accounts/999999" "Get" $null 400 | Out-Null
}

# ---------------------------------------------------------------------------
# Invariant checks - these are the guarantees the design exists to provide
# ---------------------------------------------------------------------------

Write-Host "`n=== Final state ===" -ForegroundColor Cyan
Show-Balances "after"

$transfers = Get-DbValue "SELECT count(*) FROM transfers;"
$entries   = Get-DbValue "SELECT count(*) FROM ledger_entries;"
Write-Host "  rows: transfers=$transfers ledger_entries=$entries" -ForegroundColor DarkGray

Write-Host "`n=== INVARIANT 1: every transfer's ledger entries sum to zero ===" -ForegroundColor Cyan
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

Write-Host "`n=== INVARIANT 2: money is conserved (all balances sum to zero) ===" -ForegroundColor Cyan
$totalSum = Get-DbValue "SELECT SUM(balance_minor) FROM accounts;"
if ($totalSum -eq "0") {
    Write-Host "  PASS - system total is 0; every rupee held by a customer is" -ForegroundColor Green
    Write-Host "         matched by a liability on the system account" -ForegroundColor Green
} else {
    Write-Host "  FAIL - system total is $totalSum, expected 0" -ForegroundColor Red
    Write-Host "         (money exists that was not issued through a deposit)" -ForegroundColor Red
}

Write-Host "`n=== INVARIANT 3: stored balance matches ledger sum, per account ===" -ForegroundColor Cyan
$drifted = Get-DbValue @"
SELECT count(*) FROM (
    SELECT a.id
    FROM accounts a
    LEFT JOIN ledger_entries e ON e.account_id = a.id
    GROUP BY a.id, a.balance_minor
    HAVING a.balance_minor <> COALESCE(SUM(e.amount_minor), 0)
) t;
"@
if ($drifted -eq "0") {
    Write-Host "  PASS - no account has drifted from its ledger" -ForegroundColor Green
} else {
    Write-Host "  FAIL - $drifted account(s) show drift" -ForegroundColor Red
    $detail = psql -U $DbUser -d $DbName -t -A -F " | " -c @"
SELECT a.account_number,
       a.balance_minor AS stored,
       COALESCE(SUM(e.amount_minor), 0) AS derived,
       a.balance_minor - COALESCE(SUM(e.amount_minor), 0) AS drift
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
GROUP BY a.id, a.account_number, a.balance_minor
HAVING a.balance_minor <> COALESCE(SUM(e.amount_minor), 0)
ORDER BY a.account_number;
"@
    $detail | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
}

Write-Host ""