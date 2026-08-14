@echo off
setlocal
cd /d "%~dp0"

echo ================================================================
echo Bro Liker - Release Signing Setup
echo ================================================================
echo.
echo This creates a PRIVATE release keystore on this PC.
echo Keep release-key.jks safe. Do NOT upload it to GitHub.
echo.

if exist "release-key.jks" (
  echo release-key.jks already exists. Keeping the existing key.
  goto :BASE64
)

echo Creating release keystore...
keytool -genkeypair -v ^
  -keystore "release-key.jks" ^
  -alias "bro-liker" ^
  -keyalg RSA ^
  -keysize 2048 ^
  -validity 10000 ^
  -dname "CN=Bro Liker, OU=Mobile, O=Bro Liker, L=Dhaka, ST=Dhaka, C=BD"
if errorlevel 1 (
  echo.
  echo ERROR: keytool failed. Make sure Java/JDK is installed and keytool is available.
  pause
  exit /b 1
)

:BASE64
echo.
echo Creating keystore-base64.txt...
certutil -encode -f "release-key.jks" "keystore-base64.txt" >nul
if errorlevel 1 (
  echo.
  echo ERROR: certutil failed.
  pause
  exit /b 1
)

echo.
echo ================================================================
echo DONE
 echo.
echo Files created/kept:
echo   release-key.jks
echo   keystore-base64.txt
echo.
echo GitHub Secrets:
echo   KEYSTORE_BASE64     = full contents of keystore-base64.txt
echo   KEYSTORE_PASSWORD   = the keystore password you entered
 echo   KEY_ALIAS           = bro-liker
echo   KEY_PASSWORD        = the key password you entered
 echo.
echo NEVER commit release-key.jks or keystore-base64.txt to GitHub.
echo ================================================================
pause
