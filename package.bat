@echo off
set NAME=DSP-Capture-Viewer
set VERSION=1.0.0
set DIST=%NAME%-%VERSION%

echo ============================================
echo   DSP Capture Viewer - Build Distributable
echo ============================================
echo.

echo [1/4] mvn package ...
call mvn package
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Maven build failed
    pause
    exit /b 1
)

echo [2/4] jlink - minimal JRE ...
rmdir /s /q target\runtime 2>nul
jlink --no-header-files --no-man-pages --strip-debug --add-modules java.base,java.desktop,java.logging,java.management --output target\runtime
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: jlink failed
    pause
    exit /b 1
)

echo [3/4] Assemble dist folder ...
rmdir /s /q target\%DIST% 2>nul
mkdir target\%DIST%\runtime
xcopy /q /e /y target\runtime\* target\%DIST%\runtime\ >nul
copy /y target\dsp-capture-viewer-%VERSION%.jar target\%DIST%\ >nul
xcopy /q /e /y target\lib\* target\%DIST%\lib\ >nul
for %%f in (target\%DIST%\lib\*.jar) do if %%~zf LSS 1024 del /q "%%f"

echo @echo off> target\%DIST%\DSP-Capture-Viewer.bat
echo start "" "%%~dp0runtime\bin\java.exe" --module-path "%%~dp0lib" --add-modules javafx.controls,javafx.fxml -jar "%%~dp0dsp-capture-viewer-%VERSION%.jar">> target\%DIST%\DSP-Capture-Viewer.bat
echo pause>> target\%DIST%\DSP-Capture-Viewer.bat

echo [4/4] Zip ...
powershell -Command "Compress-Archive -Path 'target\%DIST%' -DestinationPath 'target\%DIST%.zip' -Force"

echo.
echo ============================================
echo   Done: target\%DIST%.zip
echo ============================================
echo.
echo   Unzip and double-click DSP-Capture-Viewer.bat
echo   No Java installation required.
pause
