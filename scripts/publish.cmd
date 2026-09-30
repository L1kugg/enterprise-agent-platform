@echo off
rem 一键发布入口（PowerShell/CMD 双击均可）：.\scripts\publish.cmd
rem PowerShell 里裸敲 bash 会误指向 WSL，这里固定用 Git for Windows 自带的 bash。
setlocal
set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if exist "%BASH_EXE%" goto run
set "BASH_EXE=D:\Git\bin\bash.exe"
if exist "%BASH_EXE%" goto run
echo 未找到 Git Bash，请打开 Git Bash 手动执行: bash scripts/publish.sh
exit /b 1
:run
"%BASH_EXE%" "%~dp0publish.sh"
