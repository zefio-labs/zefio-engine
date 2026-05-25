@echo off
setlocal EnableDelayedExpansion

REM ###########################
REM Base Directory
REM ###########################
for %%i in ("%~dp0..") do set "BASE_DIR=%%~fi"

REM ###########################
REM Log Directory Customization
REM ###########################
set "LOG_DIR_VALUE=%BASE_DIR%\logs"
set "APP_NAME=Zefio"

REM ###########################
REM Java Home & Executable
REM ###########################
set "JAVA_HOME=%BASE_DIR%\@jdk.folder@"
set "JAVA_EXEC=%JAVA_HOME%\bin\java.exe"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ###########################
REM Classpath
REM ###########################
set "CLASSPATH=.;%BASE_DIR%\resources;%BASE_DIR%\lib\*"

REM ###########################
REM Java Version Detection
REM ###########################
set "JVM_VERSION=@jdk.version@"

echo Detected Java Version: (%JVM_VERSION%)

REM ###########################
REM Environment Type: dev or prd
REM ###########################
if "%APP_ENV%"=="" (
    set "ENV_TYPE=prd"
) else (
    set "ENV_TYPE=%APP_ENV%"
)
echo Running in '%ENV_TYPE%' mode

REM ###########################
REM JVM Options Building
REM ###########################
REM 1. Common Options (Unified to G1GC)
set "COMMON_OPTS=-XX:+UseG1GC -DNAME=%APP_NAME% -Djava.net.preferIPv4Stack=true -Dfile.encoding=utf-8"

REM 2. Memory Options (Branch by environment - controlled here instead of pom.xml)
if /I "%ENV_TYPE%"=="prd" (
    set "MEM_OPTS=-Xms2048m -Xmx2048m -XX:MaxDirectMemorySize=1024m"
) else (
    set "MEM_OPTS=-Xms512m -Xmx1024m -XX:MaxDirectMemorySize=512m"
)

REM 3. JDK Version Specific Options (Netty & GC)
if "%JVM_VERSION%"=="jdk21" (
    set "GC_LOG_OPTS=-Xlog:gc*:file=%LOG_DIR_VALUE%\gc.log:time,uptime,level,tags:filecount=5,filesize=10M"
    set "NETTY_OPTS=--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.misc=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true -Djdk.nio.maxCachedBufferSize=262144"
) else (
    set "GC_LOG_OPTS=-verbose:gc -Xloggc:%LOG_DIR_VALUE%\gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=10M"
    set "NETTY_OPTS="
)

REM 4. Environment Specific Options (Add Netty Leak Detection)
if /I "%ENV_TYPE%"=="prd" (
    set "ENV_OPTS=-Dspring.profiles.active=%ENV_TYPE% -Dio.netty.leakDetection.level=ADVANCED"
) else (
    set "ENV_OPTS=-Dspring.profiles.active=%ENV_TYPE% -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=%LOG_DIR_VALUE%\heapdump.hprof -Dio.netty.leakDetection.level=PARANOID"
)
set "ENV_OPTS=%ENV_OPTS% -DLOG_DIR=%LOG_DIR_VALUE%"

REM Base Java Opts Merge
set "BASE_JAVA_OPTS=%MEM_OPTS% %COMMON_OPTS% %GC_LOG_OPTS% %NETTY_OPTS% %ENV_OPTS%"

REM -------------------------------
REM Export local variables
REM -------------------------------
endlocal & (
    set "JAVA_HOME=%JAVA_HOME%"
    set "JAVA_EXEC=%JAVA_EXEC%"
    set "BASE_DIR=%BASE_DIR%"
    set "PATH=%PATH%"
    set "CLASSPATH=%CLASSPATH%"
    set "JVM_VERSION=%JVM_VERSION%"
    set "ENV_TYPE=%ENV_TYPE%"
    set "LOG_DIR_VALUE=%LOG_DIR_VALUE%"
    set "APP_NAME=%APP_NAME%"
    set "BASE_JAVA_OPTS=%BASE_JAVA_OPTS%"
)
