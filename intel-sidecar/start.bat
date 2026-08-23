@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
if not exist "%SCRIPT_DIR%.env" (
  echo Missing %SCRIPT_DIR%.env. Copy .env.example and set TOKEN.
  exit /b 1
)
if not exist "%SCRIPT_DIR%vendor\Spider_XHS\apis\xhs_pc_apis.py" (
  echo Spider_XHS is not installed. Run install-spider.bat first.
  exit /b 1
)
if not exist "%SCRIPT_DIR%.venv\Scripts\python.exe" (
  echo Python environment is missing. Run install-spider.bat first.
  exit /b 1
)
cd /d "%SCRIPT_DIR%"
"%SCRIPT_DIR%.venv\Scripts\python.exe" -m uvicorn app:app --host 127.0.0.1 --port 18080
