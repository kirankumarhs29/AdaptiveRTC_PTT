@echo off
REM ============================================================================
REM AdaptiveRTC CLI - Build, Run, Test, and Debug Launcher
REM ============================================================================
REM Usage: run.bat [command]
REM Commands:
REM   build          - Build the project
REM   rebuild        - Clean and rebuild the project
REM   sim            - Run the simulator
REM   test           - Run all unit tests
REM   test-packet    - Run packet test
REM   test-rtt       - Run RTT tracker test
REM   test-ecs       - Run ECS detector test
REM   test-jitter    - Run jitter buffer test
REM   integration    - Run integration test
REM   debug          - Run simulator with debug output
REM   clean          - Clean build artifacts
REM   help           - Show this help message
REM ============================================================================

setlocal enabledelayedexpansion

set CMAKE_PATH=C:\Program Files\CMake\bin\cmake.exe
set BUILD_DIR=build
set BIN_DIR=%BUILD_DIR%\bin\Debug
set LIB_DIR=%BUILD_DIR%\lib\Debug

if "%1"=="" (
    call :show_help
    exit /b 0
)

if /i "%1"=="build" (
    call :build
) else if /i "%1"=="rebuild" (
    call :rebuild
) else if /i "%1"=="sim" (
    call :run_sim
) else if /i "%1"=="test" (
    call :run_all_tests
) else if /i "%1"=="test-packet" (
    call :run_test "%BIN_DIR%\test_packet.exe"
) else if /i "%1"=="test-rtt" (
    call :run_test "%BIN_DIR%\test_rtt_tracker.exe"
) else if /i "%1"=="test-ecs" (
    call :run_test "%BIN_DIR%\test_ecs_detector.exe"
) else if /i "%1"=="test-jitter" (
    call :run_test "%BIN_DIR%\test_jitter_buffer.exe"
) else if /i "%1"=="integration" (
    call :run_test "%BIN_DIR%\integration_test.exe"
) else if /i "%1"=="debug" (
    call :debug_sim
) else if /i "%1"=="clean" (
    call :clean
) else if /i "%1"=="help" (
    call :show_help
) else (
    echo Unknown command: %1
    echo Type: run.bat help
    exit /b 1
)

exit /b 0

REM ============================================================================
REM Functions
REM ============================================================================

:build
    echo [*] Building project...
    if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
    call "%CMAKE_PATH%" -S . -B "%BUILD_DIR%"
    pushd "%BUILD_DIR%"
    call "%CMAKE_PATH%" --build . --config Debug
    popd
    echo [+] Build complete!
    exit /b 0

:rebuild
    echo [*] Cleaning old build...
    if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
    echo [*] Rebuilding project...
    if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
    call "%CMAKE_PATH%" -S . -B "%BUILD_DIR%"
    pushd "%BUILD_DIR%"
    call "%CMAKE_PATH%" --build . --config Debug
    popd
    echo [+] Rebuild complete!
    exit /b 0

:run_sim
    echo [*] Running simulator...
    if not exist "%BIN_DIR%\rtc_simulator.exe" (
        echo [-] Simulator not found. Run 'run.bat build' first.
        exit /b 1
    )
    call "%BIN_DIR%\rtc_simulator.exe"
    exit /b 0

:debug_sim
    echo [*] Running simulator with debug output...
    if not exist "%BIN_DIR%\rtc_simulator.exe" (
        echo [-] Simulator not found. Run 'run.bat build' first.
        exit /b 1
    )
    echo [*] Metrics logged to: metrics.csv
    echo [*] Press Ctrl+C to stop...
    call "%BIN_DIR%\rtc_simulator.exe"
    echo.
    echo [+] Check metrics.csv for results
    exit /b 0

:run_all_tests
    echo [*] Running all tests...
    set test_count=0
    set test_passed=0
    
    if exist "%BIN_DIR%\test_packet.exe" (
        set /a test_count+=1
        call :run_test "%BIN_DIR%\test_packet.exe" && set /a test_passed+=1
    )
    
    if exist "%BIN_DIR%\test_rtt_tracker.exe" (
        set /a test_count+=1
        call :run_test "%BIN_DIR%\test_rtt_tracker.exe" && set /a test_passed+=1
    )
    
    if exist "%BIN_DIR%\test_ecs_detector.exe" (
        set /a test_count+=1
        call :run_test "%BIN_DIR%\test_ecs_detector.exe" && set /a test_passed+=1
    )
    
    if exist "%BIN_DIR%\test_jitter_buffer.exe" (
        set /a test_count+=1
        call :run_test "%BIN_DIR%\test_jitter_buffer.exe" && set /a test_passed+=1
    )
    
    if exist "%BIN_DIR%\integration_test.exe" (
        set /a test_count+=1
        call :run_test "%BIN_DIR%\integration_test.exe" && set /a test_passed+=1
    )
    
    echo.
    echo [+] Test Results: !test_passed!/!test_count! passed
    exit /b 0

:run_test
    echo.
    echo [*] Running: %~nx1
    call "%1"
    if !errorlevel! equ 0 (
        echo [+] PASSED: %~nx1
        exit /b 0
    ) else (
        echo [-] FAILED: %~nx1 (exit code: !errorlevel!)
        exit /b 1
    )

:clean
    echo [*] Cleaning build artifacts...
    if exist "%BUILD_DIR%" (
        rmdir /s /q "%BUILD_DIR%"
        echo [+] Build directory removed
    )
    if exist "metrics.csv" (
        del metrics.csv
        echo [+] Metrics file removed
    )
    echo [+] Clean complete!
    exit /b 0

:show_help
    echo.
    echo ============================================================================
    echo            AdaptiveRTC CLI - Build, Run, Test, and Debug
    echo ============================================================================
    echo.
    echo USAGE: run.bat [command]
    echo.
    echo COMMANDS:
    echo.
    echo   BUILD AND COMPILE:
    echo     build              Build the project (incremental)
    echo     rebuild            Clean and rebuild the entire project
    echo     clean              Remove all build artifacts
    echo.
    echo   RUN APPLICATION:
    echo     sim                Run the adaptive RTC simulator
    echo     debug              Run simulator with debug info
    echo.
    echo   RUN TESTS:
    echo     test               Run all unit and integration tests
    echo     test-packet        Run packet test only
    echo     test-rtt           Run RTT tracker test only
    echo     test-ecs           Run ECS detector test only
    echo     test-jitter        Run jitter buffer test only
    echo     integration        Run integration test only
    echo.
    echo   OTHER:
    echo     help               Show this help message
    echo.
    echo EXAMPLES:
    echo.
    echo   First time setup:
    echo     run.bat build
    echo     run.bat sim
    echo.
    echo   After code changes:
    echo     run.bat rebuild
    echo     run.bat test
    echo.
    echo   Full test suite:
    echo     run.bat test-packet && run.bat test-rtt && run.bat test-ecs
    echo.
    echo   Debug output:
    echo     run.bat debug
    echo.
    echo OUTPUT FILES:
    echo   - metrics.csv          Simulation metrics (created by simulator)
    echo   - build/              CMake build directory
    echo   - build/bin/Debug/     Compiled executables
    echo   - build/lib/Debug/     Compiled libraries
    echo.
    echo ============================================================================
    echo.
    exit /b 0
