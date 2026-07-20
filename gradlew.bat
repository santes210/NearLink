@if "%DEBUG%" == "" @echo off
@rem
@rem Copyright 2015 the original authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVACMD=java
which java >nul 2>&1
if errorlevel 1 (
    echo ERROR: JAVA_HOME is not set and no java command could be found in your PATH.
    goto fail
)
goto execute

:findJavaFromJavaHome
set JAVACMD=%JAVA_HOME%\bin\java.exe
if exist "%JAVACMD%" goto execute
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
please set the JAVA_HOME variable to the location of a suitable Java development kit.
goto fail

:execute
"%JAVACMD%" -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

:fail
rem Set variable GRADLE_EXIT_CONSOLE if the project requires the exit code
if %ERRORLEVEL% == 0 set exitCode=1
if %ERRORLEVEL% NEQ 0 set exitCode=%ERRORLEVEL%
if %TEMP% == "" set gradlewExitPause=True
if "%gradlewExitPause%" == "True" pause
exit:of
