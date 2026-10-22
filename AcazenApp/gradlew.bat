@rem Gradle wrapper for Windows.
@rem Uses gradle\wrapper\gradle-wrapper.jar when present; otherwise downloads the Gradle
@rem version named in gradle-wrapper.properties on first run (see bootstrap.ps1).
@echo off
setlocal
set "APP_HOME=%~dp0"

if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" goto standard

set "GRADLE_BIN="
for /f "usebackq delims=" %%G in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%gradle\wrapper\bootstrap.ps1"`) do set "GRADLE_BIN=%%G"
if defined GRADLE_BIN goto run
echo ERROR: could not download Gradle. 1>&2
exit /b 1

:run
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%

:standard
set "JAVACMD=java.exe"
if defined JAVA_HOME set "JAVACMD=%JAVA_HOME%\bin\java.exe"
"%JAVACMD%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
