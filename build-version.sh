#!/bin/bash
# Скрипт для сборки с версионированием
# Использование: ./build-version.sh [release|dev]

BUILD_TYPE=${1:-dev}

echo "=== Сборка мода с версионированием ==="
echo "Тип сборки: $BUILD_TYPE"

if [ "$BUILD_TYPE" = "release" ]; then
    echo "Создание релизного билда..."
    ./gradlew releaseBuild
else
    echo "Создание dev билда..."
    ./gradlew build
fi

echo "=== Информация о версии ==="
./gradlew versionInfo

echo "=== Готово! ==="



