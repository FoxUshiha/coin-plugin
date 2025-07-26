@echo off
pushd %~dp0

REM limpa e recria out
if exist out rd /s /q out
mkdir out

echo Compiling with spigot‑api, vault, placeholderapi and Gson...
javac -classpath "spigot-api-1.21.6-R0.1-SNAPSHOT.jar;vault.jar;placeholderapi.jar;libs\gson-2.8.9.jar" -d out src\com\foxsrv\coin\*.java
if errorlevel 1 (
  echo Erro na compilação!
  pause
  popd
  exit /b 1
)

echo Copying resources...
copy /Y plugin.yml out >nul
copy /Y resources\config.yml out >nul

echo Shading Gson into plugin...
pushd out
jar xf ..\libs\gson-2.8.9.jar
popd

echo Packing JAR...
cd out
jar cvf Coin.jar *
move /Y Coin.jar "%~dp0"

echo Build concluído! Coin.jar gerado na raiz.
pause
popd
