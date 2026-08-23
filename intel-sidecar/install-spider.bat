@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "SPIDER_DIR=%SCRIPT_DIR%vendor\Spider_XHS"
set "SPIDER_COMMIT=e1888d712519040f5fcc294baeac4b9505b25c98"
set "PYTHON=%SCRIPT_DIR%.venv\Scripts\python.exe"

if not exist "%PYTHON%" python -m venv "%SCRIPT_DIR%.venv" || exit /b 1
"%PYTHON%" -m pip install --upgrade pip || exit /b 1
"%PYTHON%" -m pip install -r "%SCRIPT_DIR%requirements-dev.txt" || exit /b 1
if not exist "%SPIDER_DIR%\.git" (
  git clone https://github.com/cv-cat/Spider_XHS.git "%SPIDER_DIR%" || exit /b 1
)
git -C "%SPIDER_DIR%" fetch --depth 1 origin %SPIDER_COMMIT% || exit /b 1
git -C "%SPIDER_DIR%" checkout --detach %SPIDER_COMMIT% || exit /b 1
"%PYTHON%" -m pip install -r "%SPIDER_DIR%\requirements.txt" || exit /b 1
if exist "%SPIDER_DIR%\package-lock.json" (
  npm --prefix "%SPIDER_DIR%" ci || exit /b 1
) else (
  npm --prefix "%SPIDER_DIR%" install || exit /b 1
)
echo Spider_XHS installed at locked commit %SPIDER_COMMIT%
