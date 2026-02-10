@echo off

title JmProxyServer
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%.") do set "SCRIPT_DIR=%%~fI"
set "BASE_DIR=%SCRIPT_DIR%"
if not exist "%BASE_DIR%\JmProxyServer.jar" (
    for %%I in ("%SCRIPT_DIR%..") do set "BASE_DIR=%%~fI"
)
set "CONFIG_PATH=%BASE_DIR%\config\application.yml"
set "LOGBACK_CONFIG=%BASE_DIR%\config\logback.xml"
java -server -Xms512M -Xmx1024M -XX:MaxMetaspaceSize=256m -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dlogback.configurationFile="%LOGBACK_CONFIG%" -jar "%BASE_DIR%\JmProxyServer.jar" "%CONFIG_PATH%"
