@echo off

copy server\build\outputs\apk\release\server-release-unsigned.apk build-win64\app
cd build-win64\app
ren server-release-unsigned.apk scrcpy-server
cd ..\..