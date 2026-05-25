@echo off
call "%~dp0env.bat"

REM Check for PID file
set "PID_FILE=%BASE_DIR%\bin\%APP_NAME%.pid"

if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
    echo Stopping %APP_NAME% (PID: %PID%)
    taskkill /PID %PID% /T /F
    del "%PID_FILE%"
) else (
    echo No PID file found. %APP_NAME% may not be running.
)
