$env:JAVA_HOME = "C:\Java\zulu-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Set-Location $PSScriptRoot

Write-Host "[CI] Running all tests..."
mvn clean test --no-transfer-progress 2>&1 |
    Where-Object { $_ -notmatch "DB LOCK|DB RELE" }

exit $LASTEXITCODE
