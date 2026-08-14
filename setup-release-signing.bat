@echo off
setlocal
cd /d "%~dp0"

echo ================================================
echo Bro Liker - Release Signing Setup
echo ================================================
echo.

echo This will create a PRIVATE release keystore on this PC.
echo Keep release-key.jks safe. Do NOT upload it to GitHub.
echo.

if not exist "release-key.jks" (
echo Creating release keystore...
  keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias bro-liker
  if errorlevel 1 (
    echo.
echo ERROR: keytool failed. Make sure Java/JDK is installed and keytool is in PATH.
    pause
    exit /b 1
  )
) else (
echo release-key.jks already exists. It will NOT be replaced.
)

echo.
echo Creating Base64 copy for GitHub Secret...
certutil -encode -f release-key.jks keystore-base64.txt >nul
if errorlevel 1 (
echo ERROR: certutil failed.
  pause
  exit /b 1
)

echo.
echo DONE.
echo.
echo Upload these four values to GitHub Repository Secrets:
echo   KEYSTORE_BASE64  = entire content of keystore-base64.txt
echo  KEYSTORE_PASSWORD = the password you entered for the keystore
echo  KEY_ALIAS         = bro-liker
echo  KEY_PASSWORD      = the key password you entered
 echo.
echo NEVER upload release-key.jks or keystore-base64.txt to the repository.
echo.
pause
