@echo off
echo ========================================
echo Food Donation Platform - Starting...
echo ========================================
echo.

:: Check if Java is installed
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Java is not installed or not in PATH!
    echo Please install Java JDK 11 or higher.
    echo Download from: https://www.oracle.com/java/technologies/downloads/
    pause
    exit /b 1
)

echo [1/3] Compiling Java server...
javac FoodDonationServer.java
if %errorlevel% neq 0 (
    echo.
    echo ERROR: Compilation failed!
    echo Please check for syntax errors above.
    pause
    exit /b 1
)

echo [2/3] Starting Java server...
echo.
start "Food Donation Server" cmd /k "java FoodDonationServer"

:: Wait for server to start
timeout /t 3 /nobreak >nul

echo [3/3] Opening web browser...
start http://localhost:8080

echo.
echo ========================================
echo Server is running!
echo Access the app at: http://localhost:8080
echo.
echo Press Ctrl+C in the server window to stop
echo ========================================
echo.
pause