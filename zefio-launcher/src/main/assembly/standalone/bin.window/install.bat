@echo off
setlocal

REM ------------------------
REM Configuration
REM ------------------------
set "SERVICE_NAME=Zefio"
set "SERVICE_DISPLAY_NAME=Zefio Application"
set "SERVICE_DESCRIPTION=Spring Boot Java Application using NSSM"
set "NSSM_PATH=%~dp0nssm-2.24\nssm.exe"
set "APP_DIR=%~dp0"
set "RUN_BAT=%APP_DIR%run.bat"

REM ------------------------
REM Register service with NSSM
REM ------------------------
echo Installing service: %SERVICE_NAME%
"%NSSM_PATH%" install %SERVICE_NAME% "%RUN_BAT%"

REM ------------------------
REM Set service name and description
REM ------------------------
"%NSSM_PATH%" set %SERVICE_NAME% DisplayName "%SERVICE_DISPLAY_NAME%"
"%NSSM_PATH%" set %SERVICE_NAME% Description "%SERVICE_DESCRIPTION%"
"%NSSM_PATH%" set %SERVICE_NAME% Start SERVICE_AUTO_START

REM Set working directory (service execution path)
"%NSSM_PATH%" set %SERVICE_NAME% AppDirectory "%APP_DIR%"

echo Service '%SERVICE_NAME%' installed successfully.

endlocal
pause
