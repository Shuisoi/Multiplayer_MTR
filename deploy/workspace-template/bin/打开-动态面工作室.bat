@echo off
rem Open the MMTR dynamic-face studio (local web tool: view/edit the JSON that draws a vehicle's screens)
rem Extra arguments are forwarded, e.g. 打开-动态面工作室.bat -Anchors sandbox\face-preview\anchors_saf420cab_a_v36.json
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\mmtr\tools\face-studio\face-studio.ps1" -Action open %*
