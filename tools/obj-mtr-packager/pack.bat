@echo off
chcp 65001 >nul
rem Old entry point: it ran pack_vehicle.js directly, without any zip validation, and it
rem used Compress-Archive (see docs section 6 for the separator trap it caused).
rem It now forwards to the validated flow: config check -> pack -> zip check -> anchors -> naming.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\..\scripts\pack-vehicle.ps1" %*
pause
