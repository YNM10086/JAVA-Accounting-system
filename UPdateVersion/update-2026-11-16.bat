@echo off
chcp 65001 >nul
title Bookkeeping System - Update (2026-11-16)
cd /d "%~dp0"
echo.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" %*
echo.
pause
