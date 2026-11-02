@echo off
cd /d "%~dp0"
java -jar "SmartGroceryLedger.jar" %*
if errorlevel 1 pause
