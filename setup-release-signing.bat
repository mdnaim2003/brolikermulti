@echo off
setlocal EnableExtensions DisableDelayedExpansion

echo =================================================
echo Bro Liker - Release Signing Setup
echo =================================================
echo.
echo This creates a clean PRIVATE release keystore.
echo The SAME password is used for the keystore and the key.
echo Keep release-key.jks safe. Do NOT upload it to GitHub.
echo.

where keytool >nul 2>nul
if errorlevel 1 (
  echo ERROR: keytool was not found. Install JDK 17+ and reopen Command Prompt.
  exit /b 1
)

set /p "PASSWORD=Enter ONE strong release password: "
if not defined PASSWORD (
  echo ERROR: Password cannot be empty.
  exit /b 1
)
set /p "PASSWORD2=Re-enter the same password: "
if not "%PASSWORD%"=="%PASSWORD2%" (
  echo ERROR: Passwords do not match.
  exit /b 1
)

if exist release-key.jks del /f /q release-key.jks >nul 2>nul
if exist keystore-base64.txt del /f /q keystore-base64.txt >nul 2>nul

echo.
echo Creating clean release keystore...
keytool -genkeypair -v ^
  -keystore release-key.jks ^
  -storepass "%PASSWORD%" ^
  -keypass "%PASSWORD%" ^
  -alias "bro-liker" ^
  -keyalg RSA ^
  -keysize 2048 ^
  -validity 10000 ^
  -dname "CN=Bro Liker, OU=Bro Liker, O=Bro Liker, L=Dhaka, ST=Dhaka, C=BD"
if errorlevel 1 (
  echo ERROR: Failed to create release-key.jks.
  exit /b 1
)

powershell -NoProfile -Command "$bytes = [IO.File]::ReadAllBytes('release-key.jks'); $b64 = [Convert]::ToBase64String($bytes); [IO.File]::WriteAllText('keystore-base64.txt', $b64, [Text.Encoding]::ASCII)"
if errorlevel 1 (
  echo ERROR: Failed to create keystore-base64.txt.
  exit /b 1
)

if not exist keystore-base64.txt (
  echo ERROR: Failed to create keystore-base64.txt.
  exit /b 1
)

keytool -list -v -keystore release-key.jks -storepass "%PASSWORD%" -alias "bro-liker" -keypass "%PASSWORD%" >nul 2>nul
if errorlevel 1 (
  echo ERROR: Final keystore validation failed.
  exit /b 1
)

echo.
echo SUCCESS.
echo.
echo Created:
echo   release-key.jks
echo   keystore-base64.txt
echo.
echo GitHub Secrets:
echo   KEYSTORE_BASE64 = entire keystore-base64.txt content
echo   KEYSTORE_PASSWORD = the password you entered
echo   KEY_ALIAS = bro-liker
echo   KEY_PASSWORD = the SAME password you entered
echo.
echo IMPORTANT: Do NOT commit release-key.jks or keystore-base64.txt to GitHub.
pause
