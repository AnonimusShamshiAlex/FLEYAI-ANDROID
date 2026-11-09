@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
set "ADB=%~dp0.tools\android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" goto noadb
if not exist "dist\FLEY_AI.apk" goto noapk
echo Подключите телефон по USB и включите Отладку по USB.
echo Настройки - О телефоне - 7 раз нажать на Номер сборки - Для разработчиков - Отладка по USB.
echo.
"%ADB%" install -r "dist\FLEY_AI.apk"
pause
exit /b 0
:noadb
echo Сначала запустите build_apk.bat (он скачает нужные инструменты).
pause
exit /b 1
:noapk
echo Нет файла dist\FLEY_AI.apk. Сначала запустите build_apk.bat.
pause
exit /b 1
