@echo off
cd /d "%~dp0"
java -jar "SmartGroceryLedger.jar" --demo %*
if errorlevel 1 pause
