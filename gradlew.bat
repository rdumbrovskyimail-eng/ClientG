@rem
@rem Copyright 2015 the original author or authors.
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

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows (Secure Bootstrap Edition)
@rem
@rem ##########################################################################

setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem --------------------------------------------------------------------------
@rem Безопасный Bootstrap Hook: Загрузка с обязательной проверкой SHA-256
@rem --------------------------------------------------------------------------
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
    echo [gradlew] gradle-wrapper.jar not found. Initializing secure bootstrap download...
    if not exist "%APP_HOME%gradle\wrapper" mkdir "%APP_HOME%gradle\wrapper"
    
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$jar = '%WRAPPER_JAR%';" ^
        "$url = 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar';" ^
        "$expectedHash = '4ba9b0b467b7fec965b6a71e8da6eb85cf6bd9868eec2496a7576f3f0cfc24d6';" ^
        "$tls = [Net.SecurityProtocolType]::Tls12; " ^
        "try { $tls = $tls -bor [Net.SecurityProtocolType]::Tls13 } catch {}; " ^
        "[Net.ServicePointManager]::SecurityProtocol = $tls; " ^
        "(New-Object Net.WebClient).DownloadFile($url, $jar); " ^
        "if (-not (Test-Path $jar)) { Write-Error '[gradlew] Download failed.'; exit 1 }; " ^
        "$actualHash = (Get-FileHash -Path $jar -Algorithm SHA256).Hash.ToLower(); " ^
        "if ($actualHash -ne $expectedHash) { " ^
        "    Remove-Item -Force $jar; " ^
        "    Write-Error '[gradlew] SECURITY ERROR: Checksum validation failed for gradle-wrapper.jar!'; " ^
        "    exit 1; " ^
        "}"

    if "%ERRORLEVEL%" neq "0" (
        echo [gradlew] ERROR: Failed to download or verify gradle-wrapper.jar.
        exit /b 1
    )
    echo [gradlew] gradle-wrapper.jar verified and ready.
)
@rem --------------------------------------------------------------------------

@rem Опции JVM по умолчанию
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

set CLASSPATH=%WRAPPER_JAR%

@rem Определение пути к Java
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
goto fail

:execute
@rem Запуск процесса сборщика
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal