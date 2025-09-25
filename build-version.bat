@echo off
REM Скрипт для сборки с версионированием
REM Использование: build-version.bat [release|dev]

set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=dev

echo === Сборка мода с версионированием ===
echo Тип сборки: %BUILD_TYPE%

if "%BUILD_TYPE%"=="release" (
    echo Создание релизного билда...
    gradlew.bat releaseBuild
) else (
    echo Создание dev билда...
    gradlew.bat build
)

echo === Информация о версии ===
gradlew.bat versionInfo

echo === Готово! ===
pause



