@echo off
REM Build and run the Sega Mega Drive emulator without Gradle, using only a JDK 21+.
REM Usage: run.bat [path\to\rom.md]
setlocal
set DIR=%~dp0
set OUT=%DIR%out
if not exist "%OUT%" mkdir "%OUT%"
echo Compiling...
dir /s /b "%DIR%src\main\java\*.java" > "%OUT%\sources.txt"
javac -d "%OUT%" @"%OUT%\sources.txt"
if errorlevel 1 exit /b 1
echo Launching...
java -cp "%OUT%" com.segaemu.Main %*
endlocal
