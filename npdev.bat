@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
where /q py     && goto :usepy
where /q python && goto :usepython
echo npdev: python3 or python is required 1>&2
exit /b 127
:usepy
py -3 "%SCRIPT_DIR%NPDevCli\npdev_cli.py" %*
exit /b %ERRORLEVEL%
:usepython
python "%SCRIPT_DIR%NPDevCli\npdev_cli.py" %*
exit /b %ERRORLEVEL%
