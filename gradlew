#!/bin/sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Разрешение символических ссылок и определение корня проекта
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null || exit 1
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null || exit 1

APP_NAME="Gradle"
APP_BASE_NAME="${0##*/}"

# -----------------------------------------------------------------------------
# Bootstrap Hook: Безопасная загрузка gradle-wrapper.jar при его отсутствии
# -----------------------------------------------------------------------------
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -s "$WRAPPER_JAR" ]; then
    echo "[gradlew] gradle-wrapper.jar not found. Initializing bootstrap download..." >&2
    mkdir -p "$APP_HOME/gradle/wrapper" || exit 1
    JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$JAR_URL" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$JAR_URL"
    else
        echo "[gradlew] ERROR: Neither curl nor wget found to download gradle-wrapper.jar." >&2
        exit 1
    fi

    # Проверка целостности загрузки
    if [ ! -s "$WRAPPER_JAR" ]; then
        echo "[gradlew] ERROR: Failed to download gradle-wrapper.jar or file is empty." >&2
        rm -f "$WRAPPER_JAR"
        exit 1
    fi
    echo "[gradlew] gradle-wrapper.jar downloaded successfully." >&2
fi
# -----------------------------------------------------------------------------

CLASSPATH="$WRAPPER_JAR"

# Определение пути к Java
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || {
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        exit 1
    }
fi

# Запуск процесса Gradle Wrapper (чистые флаги без внутренних кавычек)
exec "$JAVACMD" \
    -Xmx64m \
    -Xms64m \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"