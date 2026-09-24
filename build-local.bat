@echo off
setlocal
set "GRADLE_USER_HOME=%~dp0.see-dev\gradle-user-home"
pushd "%~dp0"
if errorlevel 1 exit /b 1
if "%~1"=="" (
    call "%~dp0gradlew.bat" --no-daemon --project-cache-dir "%~dp0.see-dev\project-cache" build
) else (
    call "%~dp0gradlew.bat" --no-daemon --project-cache-dir "%~dp0.see-dev\project-cache" %*
)
set "SEE_BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %SEE_BUILD_EXIT%
