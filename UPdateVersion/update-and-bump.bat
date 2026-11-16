@echo off
chcp 65001 >nul
title Bookkeeping System - Update + Bump Version
cd /d "%~dp0"
echo.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" -Bump %*
echo.
pause
