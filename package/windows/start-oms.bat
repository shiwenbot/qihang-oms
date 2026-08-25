@echo off
chcp 65001 >nul
if exist "%~dp0QihangOMS.exe" (
  start "" "%~dp0QihangOMS.exe"
  exit /b 0
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package\windows\Start-QihangOms.ps1"
if errorlevel 1 pause
