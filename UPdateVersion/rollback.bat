@echo off
chcp 65001 >nul
title Bookkeeping System - Rollback
cd /d "%~dp0"
echo.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" -Rollback %*
echo.
pause
