@echo off
cd /d "%~dp0"
title Stop AndroidLock Services

echo ========================================================
echo       Stopping AndroidLock (MDMesh) Services
echo ========================================================
echo.

docker compose down
echo.
echo [OK] All AndroidLock backend services have been stopped.
echo.
pause
