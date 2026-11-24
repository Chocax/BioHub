@echo off
rem Atajo para levantar.ps1 sin tocar la politica de ejecucion de PowerShell. Acepta los mismos parametros:
rem   levantar            levantar -Sembrar            levantar -Estado            levantar -Detener
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0levantar.ps1" %*
