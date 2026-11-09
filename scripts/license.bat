@echo off
rem EggVyapar license tool launcher: runs the tool with the project's Java 17 (an older system Java cannot run it).
rem Build it once first:  gradlew :license-tool:installDist
setlocal
if exist "%USERPROFILE%\.jdks\temurin-17\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\temurin-17"
set "TOOL=%~dp0..\license-tool\build\install\license-tool\bin\license-tool.bat"
if not exist "%TOOL%" (
  echo The license tool is not built yet. In the project folder run:  gradlew :license-tool:installDist
  exit /b 1
)
call "%TOOL%" %*
