#!/bin/sh
# Gradle wrapper script
DIRNAME=$(cd "$(dirname "$0")" && pwd)
APP_BASE_NAME=$(basename "$0")
CLASSPATH=$DIRNAME/gradle/wrapper/gradle-wrapper.jar
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
