@echo off
setlocal
if /I "%~1"=="candidate" goto candidate
if /I "%~1"=="rollback" goto rollback
if /I "%~1"=="promote" goto promote
echo Usage:
echo   Release-Mobile.cmd candidate ^<version-name^> ^<version-code^> [source-commit-or-tag]
echo   Release-Mobile.cmd rollback ^<old-source-commit-or-tag^>
echo   Release-Mobile.cmd promote ^<candidate-tag^>
exit /b 2
:candidate
py -3 "%~dp0tools\release_mobile.py" candidate %2 %3 %4
exit /b %ERRORLEVEL%
:rollback
py -3 "%~dp0tools\release_mobile.py" rollback %2
exit /b %ERRORLEVEL%
:promote
py -3 "%~dp0tools\release_mobile.py" promote %2
exit /b %ERRORLEVEL%
