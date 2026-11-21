@echo off
setlocal EnableExtensions
set "TOOLCHAIN=C:\AI-Work\Toolchains\MobileBasicTool"
set "JAVA_HOME=%TOOLCHAIN%\jdk17\jdk-17.0.21+9"
set "ANDROID_HOME=%TOOLCHAIN%\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%TOOLCHAIN%\gradle-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
set "STAGE_BASE=%TOOLCHAIN%\build-staging"
set "TASK=%~1"
if not defined TASK set "TASK=all"
if /I not "%TASK%"=="all" if /I not "%TASK%"=="test" if /I not "%TASK%"=="instrumented" if /I not "%TASK%"=="debug" if /I not "%TASK%"=="release" goto usage
if not exist "%JAVA_HOME%\bin\java.exe" goto toolchain_missing
if not exist "%ANDROID_HOME%\platforms\android-37.0\android.jar" goto toolchain_missing
if not exist "%STAGE_BASE%" mkdir "%STAGE_BASE%"
if not exist "%STAGE_BASE%\" goto stage_failed
set "STAGE_ATTEMPT=0"
:choose_stage
set /a STAGE_ATTEMPT+=1
set "STAGE=%STAGE_BASE%\project-%RANDOM%-%RANDOM%-%RANDOM%"
if exist "%STAGE%" if %STAGE_ATTEMPT% lss 20 goto choose_stage
mkdir "%STAGE%" >nul 2>&1
if errorlevel 1 if %STAGE_ATTEMPT% lss 20 goto choose_stage
if errorlevel 1 goto stage_failed
robocopy %~sdp0 %STAGE% /E /R:1 /W:1 /XD ".git" ".gradle" "build" /XF "local.properties" "New-MobileProgram.ps1" "build-mobile.ps1" "*.apk" "*.aab" "*.pyc" "*.pyo" >nul
if errorlevel 8 goto stage_copy_failed
set "ANDROID_SDK_LOCAL=%ANDROID_HOME:\=/%"
echo sdk.dir=%ANDROID_SDK_LOCAL%>"%STAGE%\local.properties"
pushd "%STAGE%"
if errorlevel 1 goto stage_failed
if /I "%TASK%"=="test" call gradlew.bat clean testDebugUnitTest --no-daemon
if /I "%TASK%"=="instrumented" call gradlew.bat clean connectedDebugAndroidTest --no-daemon
if /I "%TASK%"=="debug" call gradlew.bat assembleDebug --no-daemon
if /I "%TASK%"=="all" call gradlew.bat clean testDebugUnitTest assembleDebug --no-daemon
if /I "%TASK%"=="release" call gradlew.bat clean testDebugUnitTest assembleRelease --no-daemon
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" goto build_failed
if /I "%TASK%"=="all" goto copy_apk
if /I "%TASK%"=="debug" goto copy_apk
if /I "%TASK%"=="release" goto copy_release_apk
goto success
:copy_apk
if not exist "%~dp0app\build\outputs\apk\debug" mkdir "%~dp0app\build\outputs\apk\debug"
copy /Y "%STAGE%\app\build\outputs\apk\debug\app-debug.apk" "%~dp0app\build\outputs\apk\debug\app-debug.apk" >nul
if errorlevel 1 goto apk_copy_failed
goto success
:copy_release_apk
if not exist "%~dp0app\build\outputs\apk\release" mkdir "%~dp0app\build\outputs\apk\release"
copy /Y "%STAGE%\app\build\outputs\apk\release\app-release.apk" "%~dp0app\build\outputs\apk\release\app-release.apk" >nul
if errorlevel 1 goto apk_copy_failed
:success
popd
rmdir /S /Q "%STAGE%"
echo Basic Tool build completed: %TASK%
exit /b 0
:build_failed
popd
echo Basic Tool build failed with exit code %RESULT%.
rmdir /S /Q "%STAGE%"
exit /b %RESULT%
:stage_copy_failed
echo Could not copy the Android project into the isolated build folder.
if exist "%STAGE%" rmdir /S /Q "%STAGE%"
exit /b 1
:apk_copy_failed
popd
echo Could not copy the generated APK into the project output folder.
if exist "%STAGE%" rmdir /S /Q "%STAGE%"
exit /b 1
:stage_failed
echo Could not create the isolated Android build folder.
if defined STAGE if exist "%STAGE%" rmdir /S /Q "%STAGE%"
exit /b 1
:toolchain_missing
echo The bundled JDK 17 or Android SDK API 37 is missing.
exit /b 1
:usage
echo Usage: build-mobile.cmd [test^|instrumented^|debug^|all^|release]
exit /b 2
