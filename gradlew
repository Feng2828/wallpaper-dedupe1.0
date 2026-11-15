#!/usr/bin/env sh
# 精简版 Gradle Wrapper 启动器（Linux / macOS / GitHub Actions）。
# gradle-wrapper.jar 为二进制文件，不随源码提交，由 CI 首次构建时下载；
# 也可以从任意标准 Gradle 项目的 gradle/wrapper/ 目录复制一份过来。
set -e

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "[gradlew] 缺少 gradle-wrapper.jar，请先运行：" >&2
    echo "  curl -fL -o gradle/wrapper/gradle-wrapper.jar \\" >&2
    echo "    https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" >&2
    exit 1
fi

cd "$APP_HOME"
exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
