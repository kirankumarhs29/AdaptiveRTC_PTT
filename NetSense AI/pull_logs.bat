@echo off
setlocal EnableDelayedExpansion

:: -- Config --------------------------------------------------------------------
set PKG=com.netsense.meshapp
set APP_DIR=/data/data/%PKG%/files
set LOGS=ui.log ui.log.1 core.log core.log.1
:: Output folder � one timestamped sub-folder per pull
set OUTDIR=logs\%DATE:~10,4%%DATE:~4,2%%DATE:~7,2%_%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%
set OUTDIR=%OUTDIR: =0%
:: ------------------------------------------------------------------------------

echo [NetSense] Log Pull Tool
echo.

:: 1. Verify adb is on PATH
where adb >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'adb' not found. Add Android SDK platform-tools to PATH.
    exit /b 1
)

:: 2. Collect connected devices (skip the header line)
set DEVCOUNT=0
for /f "skip=1 tokens=1" %%D in ('adb devices') do (
    if "%%D" NEQ "" (
        set /a DEVCOUNT+=1
        set "DEV[!DEVCOUNT!]=%%D"
    )
)

if %DEVCOUNT%==0 (
    echo [ERROR] No Android device connected. Enable USB debugging and reconnect.
    exit /b 1
)

:: 3. If more than one device, let the user pick
if %DEVCOUNT%==1 (
    set SERIAL=!DEV[1]!
    echo [OK] Using device: !SERIAL!
    goto :device_chosen
)

echo Multiple devices connected. Choose one:
echo.
for /l %%i in (1,1,%DEVCOUNT%) do echo   [%%i] !DEV[%%i]!
echo.
set /p CHOICE=Enter number [1-%DEVCOUNT%]: 
:: Use "call set" to force a second expansion pass so the runtime value of
:: CHOICE is used as the array index (plain !DEV[%CHOICE%]! fails inside
:: parenthesised blocks because %CHOICE% is expanded at parse time).
call set SERIAL=%%DEV[!CHOICE!]%%
if "!SERIAL!"=="" (
    echo [ERROR] Invalid choice: enter a number shown in the list above.
    exit /b 1
)
echo [OK] Using device: !SERIAL!

:device_chosen
echo.

:: 4. Verify app is installed on the chosen device
adb -s !SERIAL! shell pm list packages | findstr /i "%PKG%" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Package '%PKG%' is not installed on device !SERIAL!.
    echo         Build and install the 'androidApp' module from Android Studio first.
    exit /b 1
)
echo [OK] Package installed: %PKG%

:: 5. Verify app is debuggable (run-as only works for debug builds)
adb -s !SERIAL! shell run-as %PKG% echo ok >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'run-as %PKG%' denied. The app must be installed as a DEBUG build.
    echo.
    echo         To fix: In Android Studio select 'debug' build variant, then
    echo         Run ^> Run 'androidApp'  to install the debug APK.
    exit /b 1
)
echo [OK] App is a debug build (run-as allowed)
echo.

:: 6. Show what is actually in the app files directory (diagnostic)
echo Files in app data directory on device:
adb -s !SERIAL! shell run-as %PKG% ls -la %APP_DIR%/
echo.

:: 7. Pull log files.
::    Try #1 — exported location (no run-as needed; populated when user taps
::             "Export Logs" button in the app).
::    Try #2 — private app storage via run-as cat (always works for debug builds).
echo Pulling log files...
mkdir "%OUTDIR%" >nul 2>&1

:: Try #1: app-exported path (set by in-app "Export Logs" button)
set EXPORT_PATH=/sdcard/Android/data/%PKG%/files/logs
adb -s !SERIAL! shell ls %EXPORT_PATH%/ >nul 2>&1
if not errorlevel 1 (
    echo [INFO] Found exported logs - pulling from %EXPORT_PATH%
    for %%F in (%LOGS%) do (
        adb -s !SERIAL! pull %EXPORT_PATH%/%%F "%OUTDIR%\%%F" >nul 2>&1
        for %%S in ("%OUTDIR%\%%F") do if %%~zS==0 del "%OUTDIR%\%%F" >nul 2>&1
    )
) else (
    echo [INFO] No exported logs found - falling back to run-as pull
    echo        Tip: tap "Export Logs" in the app for faster pulls without run-as.
)

:: Try #2: private storage via run-as (authoritative latest copy).
:: Always refresh from private storage to avoid stale exported files.
for %%F in (%LOGS%) do (
    adb -s !SERIAL! exec-out run-as %PKG% cat %APP_DIR%/%%F > "%OUTDIR%\%%F" 2>nul
    for %%S in ("%OUTDIR%\%%F") do if %%~zS==0 del "%OUTDIR%\%%F" >nul 2>&1
)

:: 8. Report results
echo.
echo -- Pulled files ----------------------------------------------
set FOUND=0
for %%F in (%LOGS%) do (
    if exist "%OUTDIR%\%%F" (
        for %%S in ("%OUTDIR%\%%F") do echo   %%F  (%%~zS bytes)
        set FOUND=1
    )
)

if %FOUND%==0 (
    echo   No log files found on device yet.
    echo   Make sure you opened the app and triggered at least one action.
) else (
    echo.
    echo Output folder: %CD%\%OUTDIR%
    echo.
    echo -- Last 50 lines of ui.log -----------------------------------
    if exist "%OUTDIR%\ui.log" (
        powershell -NoProfile -Command "Get-Content '%OUTDIR%\ui.log' -Tail 50"
    ) else (
        echo   ui.log not present
    )
    echo.
    echo -- Last 50 lines of core.log ---------------------------------
    if exist "%OUTDIR%\core.log" (
        powershell -NoProfile -Command "Get-Content '%OUTDIR%\core.log' -Tail 50"
    ) else (
        echo   core.log not present
    )
)

echo.
echo Done.
endlocal
