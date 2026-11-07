@echo off
REM Abre o programa do notebook (janela propria, sem terminal).
cd /d "%~dp0"
where pyw >nul 2>&1 && (start "" pyw -3 pc\calorscan_pc.py & exit /b 0)
where pythonw >nul 2>&1 && (start "" pythonw pc\calorscan_pc.py & exit /b 0)
echo Python nao encontrado.
echo Instale em https://www.python.org/downloads/ marcando "Add python.exe to PATH" e abra de novo.
pause
