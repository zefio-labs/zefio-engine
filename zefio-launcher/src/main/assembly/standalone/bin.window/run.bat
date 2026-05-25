@echo off
setlocal

REM ================================================
REM 1) Conditionally apply JMX options
REM ================================================
if /I "%ENABLE_JMX%"=="true" (
    set "JMX_EN=true"
    if "%JMX_PORT%"=="" set "JMX_PORT=9999"
) else (
    set "JMX_EN=false"
)

if "%JMX_EN%"=="true" (
    set "JMX_OPTS=-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=%JMX_PORT% -Dcom.sun.management.jmxremote.local.only=false -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
) else (
    set "JMX_OPTS="
)

REM ================================================
REM 2) Final merge of JAVA_OPTS
REM ================================================
REM Add JMX and Maven options to BASE_JAVA_OPTS set in env.bat
set "JAVA_OPTS=%BASE_JAVA_OPTS% %JMX_OPTS% @java.opts@"

echo JAVA_OPTS set to:
echo %JAVA_OPTS%

REM ================================================
REM 5) Maintain existing PID check and execution logic
REM ================================================
set "PID_FILE=%BASE_DIR%\bin\%APP_NAME%.pid"

if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
    REM Verify if the PID is currently running
    tasklist /FI "PID eq %PID%" | findstr /I "java.exe" >nul
    if %ERRORLEVEL% == 0 (
        echo %APP_NAME% is already running with PID %PID%.
        goto :exit
    ) else (
        echo PID file found but process %PID% is not running, removing stale PID file.
        del "%PID_FILE%"
    )
)

echo Starting %APP_NAME% application...
"%JAVA_EXEC%" %JAVA_OPTS% -cp "%CLASSPATH%" io.zefio.launcher.ZefioApplication
REM If not registered as a service, use the following instead:
REM start "" "%JAVA_EXEC%" %JAVA_OPTS% -cp "%CLASSPATH%" io.zefio.launcher.ZefioApplication


REM Save the PID of the last executed java.exe
for /f "tokens=2" %%a in ('tasklist /FI "IMAGENAME eq java.exe" /FO LIST ^| findstr /I "PID" ^| sort /R') do (
    echo %%a > "%BASE_DIR%\bin\%APP_NAME%.pid"
    goto :break
)
:break
