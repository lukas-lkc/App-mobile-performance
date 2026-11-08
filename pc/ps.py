"""Executa scripts do PowerShell e devolve JSON (normal ou como administrador)."""
import base64
import json
import os
import subprocess
import tempfile
import uuid
from pathlib import Path

import winapi

PS = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), r"System32\WindowsPowerShell\v1.0\powershell.exe")
PRE = "$ProgressPreference='SilentlyContinue';$ErrorActionPreference='Continue';" \
      "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
TMP = Path(os.environ.get("LOCALAPPDATA", tempfile.gettempdir())) / "CalorScan" / "tmp"


def _enc(script):
    return base64.b64encode((PRE + script).encode("utf-16-le")).decode()


def run_json(script, timeout=90):
    """Roda o script (que deve terminar com '| ConvertTo-Json ...') e devolve o objeto."""
    p = subprocess.run([PS, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", _enc(script)], capture_output=True, timeout=timeout,
                       creationflags=winapi.NO_WINDOW | winapi.BELOW_NORMAL)
    out = p.stdout.decode("utf-8", "replace").strip()
    if not out:
        err = p.stderr.decode("utf-8", "replace").strip()
        if err:
            raise RuntimeError(err.splitlines()[0][:200])
        return None
    return json.loads(out)


def run(script, timeout=90):
    """Roda e devolve (ok, texto)."""
    p = subprocess.run([PS, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", _enc(script)], capture_output=True, timeout=timeout,
                       creationflags=winapi.NO_WINDOW | winapi.BELOW_NORMAL)
    err = p.stderr.decode("utf-8", "replace").strip()
    return p.returncode == 0 and not err, (p.stdout.decode("utf-8", "replace") + err).strip()


def run_admin(script, timeout=600):
    """Roda como administrador (o Windows mostra o aviso do UAC). Devolve (ok, texto).

    Se o programa ja esta como administrador, roda direto sem perguntar.
    """
    if winapi.is_admin():
        return run(script, timeout)
    TMP.mkdir(parents=True, exist_ok=True)
    tag = uuid.uuid4().hex
    out = TMP / f"{tag}.txt"
    ps1 = TMP / f"{tag}.ps1"
    body = (PRE + "try{ $r = & {\n" + script + "\n} 2>&1 | Out-String; "
            f"Set-Content -Encoding UTF8 -LiteralPath '{out}' -Value ('OK' + \"`n\" + $r) }}"
            f"catch{{ Set-Content -Encoding UTF8 -LiteralPath '{out}' -Value ('ERRO' + \"`n\" + $_.Exception.Message) }}")
    ps1.write_text(body, encoding="utf-8-sig")
    try:
        winapi.shell_exec(PS, f'-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{ps1}"',
                          verb="runas", wait=True, timeout=timeout, show=0)
        if not out.exists():
            return False, "O comando de administrador não terminou."
        txt = out.read_text(encoding="utf-8-sig", errors="replace")
        status, _, rest = txt.partition("\n")
        return status.strip() == "OK", rest.strip()
    finally:
        for f in (ps1, out):
            try:
                f.unlink()
            except OSError:
                pass


def q(s):
    """Aspas simples seguras para o PowerShell."""
    return "'" + str(s).replace("'", "''") + "'"
