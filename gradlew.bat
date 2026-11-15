@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if defined JAVA_HOME (set "JAVACMD=%JAVA_HOME%\bin\java.exe") else (set "JAVACMD=java.exe")
if not exist "%WRAPPER_JAR%" (
  echo [gradlew] Missing gradle-wrapper.jar. Copy it from any standard Gradle project.
  exit /b 1
)
cd /d "%APP_HOME%"
"%JAVACMD%" -Xmx64m -Xms64m -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
