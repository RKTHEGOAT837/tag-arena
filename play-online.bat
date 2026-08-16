@echo off
title Tag Arena - online
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo   Node.js was not found on this PC.
  echo   Install it from https://nodejs.org and run this file again.
  echo.
  pause
  exit /b 1
)

node online.js
echo.
echo   Stopped. Your friends' link is now dead - run this again for a new one.
pause
