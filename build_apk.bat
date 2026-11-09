@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
title FLEY AI - сборка APK для Android
echo ==========================================
echo   FLEY AI - сборка APK для телефона
echo   Первый запуск скачает JDK, Android SDK и Gradle, около 1 ГБ. Нужен интернет.
echo ==========================================
echo.

set "TOOLS=%~dp0.tools"
set "JDK=%TOOLS%\jdk"
set "SDK=%TOOLS%\android-sdk"
set "GRADLE_HOME=%TOOLS%\gradle-8.7"
if not exist "%TOOLS%" mkdir "%TOOLS%"
if not exist "dist" mkdir "dist"
if not exist "app\src\main\assets" mkdir "app\src\main\assets"

if exist "%JDK%\bin\java.exe" goto have_jdk
echo [1/5] Скачиваю JDK 17...
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\fetch.ps1" -What jdk -Dest "%TOOLS%"
if errorlevel 1 goto fail
:have_jdk
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

if exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" goto have_cmd
echo [2/5] Скачиваю Android command-line tools...
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\fetch.ps1" -What cmdline -Dest "%TOOLS%"
if errorlevel 1 goto fail
:have_cmd
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

if exist "%SDK%\platforms\android-34\android.jar" goto have_sdk
echo [3/5] Устанавливаю Android SDK, это несколько минут...
(for /l %%i in (1,1,40) do @echo y)| call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%SDK%" --licenses >nul
call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%SDK%" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
if errorlevel 1 goto fail
:have_sdk

if exist "%GRADLE_HOME%\bin\gradle.bat" goto have_gradle
echo [4/5] Скачиваю Gradle...
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\fetch.ps1" -What gradle -Dest "%TOOLS%"
if errorlevel 1 goto fail
:have_gradle

if exist "defaults.env" copy /y "defaults.env" "app\src\main\assets\defaults.env" >nul
echo [5/5] Собираю APK...
call "%GRADLE_HOME%\bin\gradle.bat" --no-daemon --console=plain assembleRelease
if errorlevel 1 goto try_debug
copy /y "app\build\outputs\apk\release\app-release.apk" "dist\FLEY_AI.apk" >nul
goto done

:try_debug
echo.
echo Релизная сборка не удалась, пробую обычную...
call "%GRADLE_HOME%\bin\gradle.bat" --no-daemon --console=plain assembleDebug
if errorlevel 1 goto fail
copy /y "app\build\outputs\apk\debug\app-debug.apk" "dist\FLEY_AI.apk" >nul

:done
echo.
echo ==========================================
echo   ГОТОВО!  Файл для телефона: dist\FLEY_AI.apk
echo   Отправьте его на телефон (Telegram, USB, облако) и откройте.
echo ==========================================
explorer "dist"
pause
exit /b 0

:fail
echo.
echo ОШИБКА. Прокрутите окно вверх и пришлите мне текст ошибки.
pause
exit /b 1
