"""Consulta ao VirusTotal (cerca de 70 antivirus de uma vez), sempre por clique.

Privacidade: o arquivo NUNCA e enviado. So a "impressao digital" (SHA-256) sai do PC, e so quando a pessoa
clica em "VirusTotal". Arquivo que o VirusTotal nunca viu volta como "desconhecido" (nao enviamos para analise).

A chave e pessoal e gratuita (virustotal.com → conta → API Key). Fica so neste PC, em
%LOCALAPPDATA%\\CalorScan\\vt_key.txt. Limite da conta gratuita: 4 consultas por minuto, 500 por dia.
"""
import hashlib
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

DATA = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "CalorScan"
KEY_FILE = DATA / "vt_key.txt"
CACHE_FILE = DATA / "vt_cache.json"
API = "https://www.virustotal.com/api/v3/files/"
GUI = "https://www.virustotal.com/gui/file/"
MAX_SIZE = 400 * 1024 ** 2   # arquivo maior que isso demora para calcular e quase nunca e virus
CACHE_DAYS = 3               # resultado guardado evita gastar o limite de consultas
GAP = 15.5                   # 4 por minuto na conta gratuita


class Limited(Exception):
    pass


class VirusTotal:
    def __init__(self):
        self.lock = threading.Lock()
        self.last_call = 0.0
        self.batch = None   # {"done": n, "total": n} durante "Conferir todos"
        self._cache = None

    # ------------------------------------------------------------------ chave
    def key(self):
        try:
            return KEY_FILE.read_text(encoding="utf-8").strip()
        except OSError:
            return ""

    def set_key(self, key):
        key = (key or "").strip()
        if key and not re.fullmatch(r"[0-9a-fA-F]{64}", key):
            raise RuntimeError("Essa chave não parece certa: a do VirusTotal tem 64 letras e números.")
        DATA.mkdir(parents=True, exist_ok=True)
        if key:
            KEY_FILE.write_text(key, encoding="utf-8")
            return "Chave do VirusTotal salva neste PC ✓"
        try:
            KEY_FILE.unlink()
        except OSError:
            pass
        return "Chave do VirusTotal apagada deste PC."

    # ------------------------------------------------------------------ cache
    def cache(self):
        if self._cache is None:
            try:
                self._cache = json.loads(CACHE_FILE.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                self._cache = {}
        return self._cache

    def _save(self):
        DATA.mkdir(parents=True, exist_ok=True)
        old = time.time() - 30 * 86400
        c = {k: v for k, v in self.cache().items() if v.get("t", 0) > old}
        self._cache = c
        CACHE_FILE.write_text(json.dumps(c, ensure_ascii=False), encoding="utf-8")

    def by_path(self, path):
        """Resultado guardado para um arquivo (sem consultar de novo)."""
        return self.cache().get("p:" + (path or "").lower())

    # ------------------------------------------------------------------ consulta
    def check(self, path, force=False):
        if not self.key():
            raise RuntimeError("Coloque sua chave do VirusTotal primeiro (aba Segurança).")
        if not path or not os.path.isfile(path):
            raise RuntimeError("Arquivo não encontrado (talvez já tenha sido removido).")
        size = os.path.getsize(path)
        if size > MAX_SIZE:
            raise RuntimeError("Arquivo grande demais para conferir (mais de 400 MB).")
        st = os.stat(path)
        pkey = "p:" + path.lower()
        old = self.cache().get(pkey)
        if old and not force and old.get("mtime") == int(st.st_mtime) and old.get("size") == size and \
                time.time() - old.get("t", 0) < CACHE_DAYS * 86400:
            return old
        sha = sha256(path)
        res = self._lookup(sha)
        res.update(path=path, sha256=sha, size=size, mtime=int(st.st_mtime), t=time.time(), link=GUI + sha)
        with self.lock:
            self.cache()[pkey] = res
            self._save()
        return res

    def _lookup(self, sha):
        with self.lock:
            wait = self.last_call + GAP - time.time()
            if self.batch is None and wait > 0:
                raise Limited(f"A conta gratuita do VirusTotal deixa 4 consultas por minuto. Espere {int(wait) + 1} s.")
            if wait > 0:
                time.sleep(wait)
            self.last_call = time.time()
        req = urllib.request.Request(API + sha, headers={"x-apikey": self.key(), "accept": "application/json",
                                                         "User-Agent": "CalorScanPC"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:  # noqa: S310 (URL fixa https)
                data = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"verdict": "desconhecido", "malicious": 0, "suspicious": 0, "total": 0, "label": "",
                        "text": "O VirusTotal nunca viu esse arquivo. Não quer dizer que é vírus, mas também não há "
                                "garantia: programas feitos sob medida (inclusive vírus novos) aparecem assim."}
            if e.code == 401:
                raise RuntimeError("O VirusTotal recusou a chave. Confira se copiou a chave inteira.") from e
            if e.code == 429:
                raise Limited("Limite de consultas do VirusTotal atingido (4 por minuto / 500 por dia). Tente daqui a pouco.") from e
            raise RuntimeError(f"O VirusTotal respondeu com erro {e.code}.") from e
        except (urllib.error.URLError, TimeoutError) as e:
            raise RuntimeError("Sem conexão com o VirusTotal. Confira a internet.") from e
        a = (data.get("data") or {}).get("attributes") or {}
        s = a.get("last_analysis_stats") or {}
        mal, susp = int(s.get("malicious", 0)), int(s.get("suspicious", 0))
        total = sum(int(v) for k, v in s.items() if k in ("malicious", "suspicious", "undetected", "harmless"))
        label = ((a.get("popular_threat_classification") or {}).get("suggested_threat_label") or "")
        if mal >= 5:
            verdict, text = "virus", f"{mal} de {total} antivírus dizem que é vírus" + (f" ({label})" if label else "") + "."
        elif mal + susp >= 2:
            verdict, text = "suspeito", f"{mal + susp} de {total} antivírus acham suspeito" + (f" ({label})" if label else "") + \
                ". Pode ser adware/programa indesejado ou falso alarme."
        elif mal + susp == 1:
            verdict, text = "limpo", f"Só 1 de {total} antivírus reclamou: quase sempre é falso alarme."
        else:
            verdict, text = "limpo", f"Nenhum dos {total} antivírus achou problema."
        when = a.get("last_analysis_date")
        return {"verdict": verdict, "malicious": mal, "suspicious": susp, "total": total, "label": label, "text": text,
                "analysed": int(when) if when else None, "name": a.get("meaningful_name") or ""}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


VT = VirusTotal()
