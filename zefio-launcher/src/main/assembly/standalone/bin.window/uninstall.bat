@echo off
setlocal

REM ------------------------
REM Configuration
REM ------------------------
set "SERVICE_NAME=Zefio"
set "NSSM_PATH=%~dp0nssm-2.24\nssm.exe"

REM ------------------------
REM Stop and uninstall service with NSSM
REM ------------------------
echo Stopping and uninstalling service: %SERVICE_NAME%
"%NSSM_PATH%" stop %SERVICE_NAME%
"%NSSM_PATH%" remove %SERVICE_NAME% confirm

echo Service '%SERVICE_NAME%' removed successfully.

endlocal
pause
