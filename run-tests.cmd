@echo off
chcp 65001 >nul

set JAVA_HOME=C:\Java\zulu-21
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d %~dp0

echo [CI] Running all tests...
mvn clean test --no-transfer-progress 2>&1 | findstr /v "DB LOCK\|DB RELE"

exit /b %ERRORLEVEL%
