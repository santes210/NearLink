#!/usr/bin/env sh

#
# Copyright © 2015-2021 the original authors.
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

if [ -n "$DEBUG" ] ; then
    echo "$@"
fi

app_home=$(dirname "$(readlink -f "$0" 2>/dev/null || stat -f "$0" 2>/dev/null || echo "$0")")

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the java executable
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || { echo >&2 "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."; exit 1; }
fi

# Increase the maximum file descriptors if we can
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=$(ulimit -n -H 2>/dev/null)
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD_LIMIT" -gt "4096" ] ; then
            MAX_FD_LIMIT="4096"
        fi
        ulimit -n "$MAX_FD_LIMIT" 2>/dev/null
    fi
fi

# Determine Gradle home
GRADLE_HOME=$app_home/gradle/wrapper

# Execute Gradle
exec "$JAVACMD"     -Dorg.gradle.appname="$APP_BASE_NAME"     -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar"     org.gradle.wrapper.GradleWrapperMain     "$@"
