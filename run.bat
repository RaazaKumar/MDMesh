@echo off
cd /d "%~dp0"
title AndroidLock (MDMesh) - All Services

echo ========================================================
echo         Starting AndroidLock (MDMesh) Suite
echo ========================================================
echo.

:: 1. Check if Docker is running
docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker is not running!
    echo Please start Docker Desktop first, then run this script again.
    echo.
    pause
    exit /b 1
)

:: 2. Start Docker Containers (Backend + DB + Caddy + Supervisor)
echo [1/2] Starting Backend & PostgreSQL (Docker)...
docker compose up -d
if %errorlevel% neq 0 (
    echo [ERROR] Failed to start Docker containers!
    pause
    exit /b 1
)
echo [OK] Docker containers are up and running.
echo.

:: 3. Launch Web Frontend Console
echo [2/2] Starting Web Console (React / Vite)...
echo.
echo ========================================================
echo   AndroidLock is Ready:
echo   - Web Console: http://localhost:5173
echo   - Backend API: http://localhost:8080/rest/
echo.
echo   [Tip] Press Ctrl+C to stop the Web Console.
echo   [Tip] Run stop.bat to shut down Docker containers.
echo ========================================================
echo.

cd web
call npm run dev
