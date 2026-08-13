@echo off
setlocal enabledelayedexpansion
set NAME=DSP-Capture-Viewer
set VERSION=1.0.0
set JAR=dsp-capture-viewer-%VERSION%.jar
set MAIN=com.qx.dspcapture.App
set DIST=target\%NAME%

echo ============================================
echo   DSP Capture Viewer - Build EXE
echo ============================================
echo.

echo [1/3] mvn package + jlink ...
call mvn package -q
if %ERRORLEVEL% NEQ 0 ( echo ERROR: mvn failed & pause & exit /b 1 )

rmdir /s /q target\runtime 2>nul
jlink --no-header-files --no-man-pages --strip-debug --add-modules java.base,java.desktop,java.logging,java.management,java.scripting,jdk.unsupported --output target\runtime
if %ERRORLEVEL% NEQ 0 ( echo ERROR: jlink failed & pause & exit /b 1 )

echo [2/3] jpackage ...
rmdir /s /q "%DIST%" 2>nul
rmdir /s /q target\staging 2>nul
mkdir target\staging\lib
copy /y target\%JAR% target\staging\ >nul
copy /y target\lib\*win.jar target\staging\lib\ >nul

jpackage --name "%NAME%" --app-version %VERSION% --input target\staging --main-jar %JAR% --main-class %MAIN% --runtime-image target\runtime --icon src\main\resources\ICON.ico --java-options "--module-path lib --add-modules javafx.controls,javafx.fxml" --type app-image --dest target
if %ERRORLEVEL% NEQ 0 ( echo ERROR: jpackage failed & pause & exit /b 1 )

echo [3/3] Fix launcher + zip ...
copy /y "%DIST%\app\%JAR%" "%DIST%\" >nul
xcopy /q /e /y "%DIST%\app\lib\*" "%DIST%\lib\" >nul
copy /y "%DIST%\app\%NAME%.cfg" "%DIST%\" >nul
powershell -Command "Compress-Archive -Path '%DIST%' -DestinationPath '%DIST%.zip' -Force"

echo.
echo ============================================
echo   Done: %DIST%.zip
echo ============================================
echo   Send this zip to users.
echo   Unzip and double-click %NAME%.exe
echo   No Java installation required.
pause
