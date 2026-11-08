"""Espaco em disco: analise de pastas, arquivos grandes, por data, por tipo, duplicados e limpeza."""
import glob
import hashlib
import os
import threading
import time
from collections import defaultdict
from pathlib import Path

import ps
import suspeitos as sus
import winapi

MIN_BIG = 1 << 20          # arquivos >= 1 MB entram nas listas
TREE_DEPTH = 4             # tamanhos de pasta guardados ate 4 niveis abaixo da raiz
CATS = {
    "Vídeos": "mp4 mkv avi mov wmv flv webm m4v mpg mpeg 3gp ts vob",
    "Imagens": "jpg jpeg png gif bmp webp heic tif tiff raw cr2 nef arw dng psd svg ico",
    "Músicas": "mp3 wav flac aac ogg m4a wma opus",
    "Documentos": "pdf doc docx xls xlsx ppt pptx txt odt ods csv rtf epub md",
    "Compactados": "zip rar 7z tar gz bz2 xz iso img cab",
    "Programas": "exe msi msix appx dll sys apk jar bat cmd",
    "Máquinas virtuais e jogos": "vhd vhdx vmdk vdi pak bin dat obb",
    "Temporários e logs": "tmp log etl dmp bak old cache",
}
EXT = {e: c for c, es in CATS.items() for e in es.split()}
PROTECTED_NAMES = {"pagefile.sys", "hiberfil.sys", "swapfile.sys", "dumpstack.log.tmp", "bootmgr"}


def category(name):
    return EXT.get(name.rsplit(".", 1)[-1].lower(), "Outros") if "." in name else "Outros"


def protected(path):
    p = sus.norm(path).rstrip("\\")
    if len(p) <= 3:
        return True  # raiz do disco
    if os.path.basename(p) in PROTECTED_NAMES:
        return True
    if (p + "\\").startswith(sus.SYSROOT) or any((p + "\\").startswith(x) for x in sus.PFILES):
        return True
    prof = sus.PROFILE.rstrip("\\")
    if p in (prof, os.path.dirname(prof), prof + "\\appdata"):
        return True
    return p.startswith(sus.PDATA + "microsoft")


class DiskScan:
    def __init__(self):
        self.lock = threading.Lock()
        self.state = {"phase": "idle"}
        self.cancel = False
        self.root = None
        self.tree = {}      # pasta -> [bytes, arquivos]
        self.big = []       # [tamanho, data, caminho, categoria]
        self.cats = {}
        self.dups = None
        self.total = 0

    # ------------------------------------------------------------------ varredura
    def scan(self, root):
        root = os.path.abspath(root)
        if not root.endswith("\\") and len(root) == 2:
            root += "\\"
        self.cancel = False
        self.state = {"phase": "scanning", "root": root, "files": 0, "bytes": 0, "dirs": 0, "current": root,
                      "started": time.time()}
        tree = {root: [0, 0]}
        big = []
        cats = defaultdict(lambda: [0, 0])
        stack = [(root, 0, (root,))]
        files = nbytes = ndirs = 0
        last = time.time()
        while stack:
            if self.cancel:
                self.state = {"phase": "idle", "msg": "Análise cancelada"}
                return
            d, depth, anc = stack.pop()
            try:
                it = os.scandir(d)
            except OSError:
                continue
            ndirs += 1
            with it:
                for e in it:
                    try:
                        if e.is_dir(follow_symlinks=False):
                            if e.is_symlink() or getattr(e, "is_junction", lambda: False)():
                                continue
                            st = e.stat(follow_symlinks=False)
                            if getattr(st, "st_file_attributes", 0) & 0x400:  # ponto de reparo
                                continue
                            nd = depth + 1
                            if nd <= TREE_DEPTH:
                                tree[e.path] = [0, 0]
                                stack.append((e.path, nd, anc + (e.path,)))
                            else:
                                stack.append((e.path, nd, anc))
                            continue
                        st = e.stat(follow_symlinks=False)
                    except OSError:
                        continue
                    sz = st.st_size
                    files += 1
                    nbytes += sz
                    for a in anc:
                        x = tree[a]
                        x[0] += sz
                        x[1] += 1
                    c = category(e.name)
                    cc = cats[c]
                    cc[0] += sz
                    cc[1] += 1
                    if sz >= MIN_BIG:
                        big.append((sz, int(st.st_mtime), e.path, c))
            if time.time() - last > 0.5:
                last = time.time()
                self.state.update(files=files, bytes=nbytes, dirs=ndirs, current=d)
        with self.lock:
            self.root, self.tree, self.big, self.cats, self.total = root, tree, big, dict(cats), nbytes
            self.dups = None
        self.state = {"phase": "done", "root": root, "files": files, "bytes": nbytes, "dirs": ndirs,
                      "secs": round(time.time() - self.state["started"]), "t": time.time()}

    # ------------------------------------------------------------------ consultas
    def children(self, path):
        path = path or self.root
        if not path or path not in self.tree:
            return None
        pref = path if path.endswith("\\") else path + "\\"
        kids = [{"path": p, "name": p[len(pref):], "bytes": v[0], "files": v[1], "protected": protected(p)}
                for p, v in self.tree.items() if p.startswith(pref) and "\\" not in p[len(pref):] and p != path]
        kids.sort(key=lambda k: -k["bytes"])
        loose = self.tree[path][0] - sum(k["bytes"] for k in kids)
        parent = os.path.dirname(path.rstrip("\\")) if path != self.root else None
        if parent and len(parent) == 2:
            parent += "\\"
        deeper = path.count("\\") - self.root.rstrip("\\").count("\\") >= TREE_DEPTH
        return {"path": path, "bytes": self.tree[path][0], "files": self.tree[path][1], "kids": kids[:200],
                "loose": max(0, loose), "parent": parent if parent in self.tree else None, "deeper": deeper}

    def files(self, sort="size", cat="", q="", min_mb=1, offset=0, limit=100, older_days=0):
        now = time.time()
        rows = self.big
        if cat:
            rows = [r for r in rows if r[3] == cat]
        if q:
            ql = q.lower()
            rows = [r for r in rows if ql in r[2].lower()]
        if min_mb and min_mb > 1:
            rows = [r for r in rows if r[0] >= min_mb * MIN_BIG]
        if older_days:
            rows = [r for r in rows if now - r[1] >= older_days * 86400]
        key = {"size": lambda r: -r[0], "new": lambda r: -r[1], "old": lambda r: r[1],
               "name": lambda r: os.path.basename(r[2]).lower()}.get(sort, lambda r: -r[0])
        rows = sorted(rows, key=key)
        return {"total": len(rows), "bytes": sum(r[0] for r in rows),
                "rows": [{"size": r[0], "mtime": r[1], "path": r[2], "cat": r[3], "protected": protected(r[2])}
                         for r in rows[offset:offset + limit]]}

    def summary(self):
        if not self.root:
            return None
        cats = sorted(({"cat": c, "bytes": v[0], "files": v[1]} for c, v in self.cats.items()), key=lambda x: -x["bytes"])
        return {"root": self.root, "bytes": self.total, "cats": cats,
                "dups": None if self.dups is None else {"groups": len(self.dups),
                                                         "waste": sum(g["waste"] for g in self.dups)}}

    # ------------------------------------------------------------------ duplicados
    def find_dups(self):
        if not self.big:
            return
        self.cancel = False
        by_size = defaultdict(list)
        for sz, mt, p, c in self.big:
            low = p.lower()
            # so arquivos da pessoa: copias internas de apps (AppData/ProgramData) quebrariam programas
            if not protected(p) and "\\appdata\\" not in low and not low.startswith(sus.PDATA) \
                    and "\\$recycle.bin\\" not in low and "\\windowsapps\\" not in low:
                by_size[sz].append((p, mt))
        cand = {s: v for s, v in by_size.items() if len(v) > 1}
        total = sum(s * len(v) for s, v in cand.items())
        done = 0
        self.state = {**self.state, "phase": "hashing", "hash_done": 0, "hash_total": total}
        groups = []
        for sz, files in sorted(cand.items(), key=lambda kv: -kv[0]):
            if self.cancel:
                self.state = {**self.state, "phase": "done"}
                return
            part = defaultdict(list)
            for p, mt in files:
                h = _hash(p, sz, quick=True)
                if h:
                    part[h].append((p, mt))
            for same in part.values():
                if len(same) < 2:
                    continue
                full = defaultdict(list)
                for p, mt in same:
                    h = _hash(p, sz, quick=False) if sz > 256 * 1024 else "q"
                    if h:
                        full[h].append({"path": p, "mtime": mt})
                for h, fs in full.items():
                    if len(fs) > 1:
                        fs.sort(key=lambda f: f["mtime"])
                        groups.append({"size": sz, "count": len(fs), "waste": sz * (len(fs) - 1), "files": fs,
                                       "cat": category(fs[0]["path"])})
            done += sz * len(files)
            self.state["hash_done"] = done
        groups.sort(key=lambda g: -g["waste"])
        self.dups = groups
        self.state = {**self.state, "phase": "done", "dups_t": time.time()}

    def drop(self, paths):
        """Tira da memoria os arquivos que foram para a Lixeira."""
        s = set(paths)
        with self.lock:
            for sz, mt, p, c in self.big:
                if p in s:
                    for a in list(self.tree):
                        if p.startswith(a if a.endswith("\\") else a + "\\"):
                            self.tree[a][0] -= sz
                            self.tree[a][1] -= 1
                    self.total -= sz
                    if c in self.cats:
                        self.cats[c][0] -= sz
                        self.cats[c][1] -= 1
            self.big = [r for r in self.big if r[2] not in s]
            for p in s:  # pastas inteiras
                pre = p.rstrip("\\") + "\\"
                if p in self.tree:
                    gone = self.tree[p][0]
                    for a in list(self.tree):
                        if p.startswith(a.rstrip("\\") + "\\"):
                            self.tree[a][0] -= gone
                    self.tree = {k: v for k, v in self.tree.items() if k != p and not k.startswith(pre)}
                    self.big = [r for r in self.big if not r[2].startswith(pre)]
            if self.dups:
                for g in self.dups:
                    g["files"] = [f for f in g["files"] if f["path"] not in s]
                    g["count"] = len(g["files"])
                    g["waste"] = g["size"] * max(0, g["count"] - 1)
                self.dups = [g for g in self.dups if g["count"] > 1]


def _hash(path, size, quick):
    try:
        h = hashlib.blake2b(digest_size=16)
        with open(path, "rb") as f:
            if quick:
                h.update(f.read(65536))
                if size > 131072:
                    f.seek(size - 65536)
                    h.update(f.read(65536))
            else:
                while True:
                    b = f.read(1 << 20)
                    if not b:
                        break
                    h.update(b)
        return h.hexdigest()
    except OSError:
        return None


DISK = DiskScan()


# ----------------------------------------------------------------------------- limpeza
def _cleanup_targets():
    la = os.environ.get("LOCALAPPDATA", "")
    sr = os.environ.get("SystemRoot", r"C:\Windows")
    pd = os.environ.get("ProgramData", r"C:\ProgramData")
    tmp = os.environ.get("TEMP", os.path.join(la, "Temp"))
    return [
        {"id": "temp", "name": "Arquivos temporários (seus)", "admin": False, "paths": [tmp],
         "why": "Sobras de instalações e programas. Pode apagar sem medo; o que estiver em uso fica."},
        {"id": "wintemp", "name": "Arquivos temporários do Windows", "admin": True, "paths": [os.path.join(sr, "Temp")],
         "why": "Sobras do próprio Windows. Precisa de permissão de administrador."},
        {"id": "browser", "name": "Cache dos navegadores", "admin": False,
         "paths": glob.glob(os.path.join(la, r"Google\Chrome\User Data\*\Cache")) +
         glob.glob(os.path.join(la, r"Google\Chrome\User Data\*\Code Cache")) +
         glob.glob(os.path.join(la, r"Microsoft\Edge\User Data\*\Cache")) +
         glob.glob(os.path.join(la, r"Microsoft\Edge\User Data\*\Code Cache")) +
         glob.glob(os.path.join(la, r"Opera Software\*\Cache")) +
         glob.glob(os.path.join(la, r"Opera Software\*\*\Cache")) +
         glob.glob(os.path.join(la, r"BraveSoftware\Brave-Browser\User Data\*\Cache")) +
         glob.glob(os.path.join(la, r"Mozilla\Firefox\Profiles\*\cache2")),
         "why": "Cópias de sites visitados. Os sites abrem um pouco mais devagar na primeira vez. Não apaga senhas nem histórico."},
        {"id": "wu", "name": "Downloads do Windows Update", "admin": True,
         "paths": [os.path.join(sr, r"SoftwareDistribution\Download")],
         "why": "Atualizações já instaladas. O Windows baixa de novo se precisar."},
        {"id": "dumps", "name": "Relatórios de erro e travamentos", "admin": True,
         "paths": [os.path.join(la, "CrashDumps"), os.path.join(sr, "Minidump"),
                   os.path.join(pd, r"Microsoft\Windows\WER\ReportArchive"),
                   os.path.join(pd, r"Microsoft\Windows\WER\ReportQueue"), os.path.join(sr, "MEMORY.DMP")],
         "why": "Arquivos gerados quando algo trava. Só servem para técnicos."},
        {"id": "shaders", "name": "Cache da placa de vídeo (shaders)", "admin": False,
         "paths": [os.path.join(la, r"NVIDIA\DXCache"), os.path.join(la, r"NVIDIA\GLCache"),
                   os.path.join(la, "D3DSCache"), os.path.join(la, r"AMD\DxCache"), os.path.join(la, r"AMD\DxcCache")],
         "why": "Os jogos recriam aos poucos (o primeiro carregamento pode engasgar um pouco)."},
        {"id": "thumbs", "name": "Miniaturas de fotos e vídeos", "admin": False,
         "paths": glob.glob(os.path.join(la, r"Microsoft\Windows\Explorer\thumbcache_*.db")),
         "why": "O Windows recria sozinho quando você abrir as pastas."},
    ]


def _size(path):
    if os.path.isfile(path):
        try:
            return os.path.getsize(path), 1
        except OSError:
            return 0, 0
    total = n = 0
    for dp, dn, fn in os.walk(path):
        for f in fn:
            try:
                total += os.path.getsize(os.path.join(dp, f))
                n += 1
            except OSError:
                pass
    return total, n


def cleanup_info():
    out = []
    for t in _cleanup_targets():
        b = n = 0
        for p in t["paths"]:
            if os.path.exists(p):
                x, y = _size(p)
                b += x
                n += y
        out.append({k: v for k, v in t.items() if k != "paths"} | {"bytes": b, "files": n})
    rb, rn = winapi.recycle_bin_info()
    out.append({"id": "recycle", "name": "Lixeira", "admin": False, "bytes": rb, "files": rn,
                "why": "Arquivos que você já apagou. Esvaziar apaga de vez."})
    return out


def cleanup(ids, say):
    freed = 0
    targets = {t["id"]: t for t in _cleanup_targets()}
    admin_paths = []
    for i in ids:
        if i == "recycle":
            b, _ = winapi.recycle_bin_info()
            winapi.empty_recycle_bin()
            freed += b
            continue
        t = targets.get(i)
        if not t:
            continue
        say(f"Limpando: {t['name']}…")
        if t["admin"] and not winapi.is_admin():
            admin_paths += t["paths"]
            continue
        for p in t["paths"]:
            freed += _wipe(p)
    if admin_paths:
        say("Pedindo permissão de administrador para limpar as pastas do Windows…")
        before = sum(_size(p)[0] for p in admin_paths if os.path.exists(p))
        script = "; ".join(
            (f"Get-ChildItem -LiteralPath {ps.q(p)} -Force -EA SilentlyContinue | Remove-Item -Recurse -Force -EA SilentlyContinue"
             if os.path.isdir(p) else f"Remove-Item -LiteralPath {ps.q(p)} -Force -EA SilentlyContinue")
            for p in admin_paths if os.path.exists(p))
        if script:
            ps.run_admin(script, timeout=900)
        after = sum(_size(p)[0] for p in admin_paths if os.path.exists(p))
        freed += max(0, before - after)
    return freed


def _wipe(path):
    """Apaga o conteudo (o que estiver em uso fica). Devolve bytes liberados."""
    freed = 0
    if os.path.isfile(path):
        try:
            s = os.path.getsize(path)
            os.remove(path)
            return s
        except OSError:
            return 0
    for dp, dn, fn in os.walk(path, topdown=False):
        for f in fn:
            fp = os.path.join(dp, f)
            try:
                s = os.path.getsize(fp)
                os.remove(fp)
                freed += s
            except OSError:
                pass
        for d in dn:
            try:
                os.rmdir(os.path.join(dp, d))
            except OSError:
                pass
    return freed


def to_recycle(paths):
    bad = [p for p in paths if protected(p)]
    if bad:
        raise RuntimeError("Por segurança, não mexo em arquivos do Windows ou de programas: " + bad[0])
    gone = winapi.recycle(paths)
    DISK.drop(gone)
    return gone
