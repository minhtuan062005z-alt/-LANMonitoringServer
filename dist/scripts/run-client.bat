@echo off
setlocal
set "APP_HOME=%~dp0.."
java %LANMONITORING_JAVA_OPTS% -jar "%APP_HOME%\LANMonitoringServer.jar" --client
endlocal
