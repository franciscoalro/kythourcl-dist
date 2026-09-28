#!/usr/bin/env python3
"""
Audita o contrato que o plugin Kotlin (Tomato.kt) envia contra o contrato medido.

Cada caso espelha exatamente o que o plugin faz hoje (v159) e o que a medicao
do bundle Hermes + trafego real provou. Nada aqui e inferido do nome da rota.

O token e lido do proprio plugin em disco e nunca e impresso.
"""
import json
import re
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

PLUGIN = Path("/root/kythourcl-dist/Tomato/src/main/kotlin/com/tomato/Tomato.kt")
BASE = "https://prod-api.tomatoanimes.com"
UA = "tomato-android"


def load_token() -> str:
    txt = PLUGIN.read_text(encoding="utf-8", errors="ignore")
    m = re.search(r'BEARER_TOKEN\s*=\s*"([^"]+)"', txt)
    if not m:
        sys.exit("BEARER_TOKEN nao encontrado no plugin")
    return m.group(1)


TOKEN = load_token()

HDR_AUTH = {
    "User-Agent": UA,
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/json",
    "Content-Type": "application/json",
}
HDR_BARE = {"User-Agent": UA, "Accept": "application/json", "Content-Type": "application/json"}


def call(method, path, body=None, headers=None, timeout=20):
    """Retorna (status, bytes, nota). Nunca levanta em erro HTTP: o 500 da
    borda e informacao, nao excecao."""
    data = None
    if body is not None:
        data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers or HDR_AUTH, method=method)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, raw, f"{time.time()-t0:.2f}s"
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, raw, f"{time.time()-t0:.2f}s"
    except Exception as e:
        return None, b"", f"{type(e).__name__}: {e}"


def count_episodes(raw):
    try:
        j = json.loads(raw)
        d = j.get("data")
        return len(d) if isinstance(d, list) else 0
    except Exception:
        return -1


def count_results(raw):
    try:
        j = json.loads(raw)
        d = j.get("data")
        if isinstance(d, dict):
            r = d.get("result")
            if isinstance(r, list):
                return len(r)
        r = j.get("result")
        return len(r) if isinstance(r, list) else -1
    except Exception:
        return -1


def show(title, code, raw, nota, metric=None):
    body = raw[:70].decode("utf-8", "replace").replace("\n", " ")
    extra = f" | {metric}" if metric is not None else ""
    print(f"[{str(code):>4}] {title:<52} {nota:>7}{extra}")
    print(f"        corpo: {body}")


print("=" * 100)
print("1) BUSCA -- o plugin envia page=1 (SearchReq default e explicito page = 1)")
print("=" * 100)
for label, body, hdr in [
    ("plugin hoje: page=1, content_type=anime", {"search": "bleach", "content_type": "anime", "page": 1, "tags": []}, HDR_AUTH),
    ("medido: page=0, content_type=anime", {"search": "bleach", "content_type": "anime", "page": 0, "tags": []}, HDR_AUTH),
]:
    code, raw, nota = call("POST", "/v2/content/search", body, hdr)
    show(label, code, raw, nota, f"resultados={count_results(raw)}")

print()
print("=" * 100)
print("2) EPISODIOS -- o plugin envia page=1 e order=\"asc\" (minusculo)")
print("=" * 100)
SEASON = 125
for label, body, hdr in [
    ("plugin hoje: page=1, order=\"asc\"", {"page": 1, "order": "asc"}, HDR_AUTH),
    ("medido: page=0, order=\"ASC\"", {"page": 0, "order": "ASC"}, HDR_AUTH),
    ("page=0, order=\"asc\" (minusculo)", {"page": 0, "order": "asc"}, HDR_AUTH),
    ("page=0, order=\"DESC\"", {"page": 0, "order": "DESC"}, HDR_AUTH),
    ("page=0, order=\"ASC\" SEM Authorization", {"page": 0, "order": "ASC"}, HDR_BARE),
    ("page=0, order=\"ASC\" com token no corpo", {"page": 0, "order": "ASC", "token": TOKEN}, HDR_BARE),
]:
    code, raw, nota = call("POST", f"/season/{SEASON}/episodes", body, hdr)
    show(label, code, raw, nota, f"episodios={count_episodes(raw)}")

print()
print("=" * 100)
print("3) Sonda de origem morta (isOriginDead) -- o que ela devolve agora")
print("=" * 100)
code, raw, nota = call("GET", f"/zzz-nao-existe-{int(time.time()*1000)}", None, {"User-Agent": UA})
show("GET /zzz-nao-existe-<nonce>", code, raw, nota)

print()
print("=" * 100)
print("4) STREAM -- precisa de ep_id, e o m3u8 precisa continuar vivo")
print("=" * 100)
for ep in (4664,):
    code, raw, nota = call("GET", f"/v2/anime/episode/{ep}/stream")
    try:
        st = json.loads(raw).get("streams") or {}
        keys = [k for k, v in st.items() if v]
    except Exception:
        keys = []
    show(f"GET /v2/anime/episode/{ep}/stream", code, raw, nota, f"streams={keys}")

# confere que o m3u8 do fhd realmente responde (o loadLinks usa M3u8Helper antes)
try:
    code, raw, nota = call("GET", f"/v2/anime/episode/4664/stream")
    fhd = json.loads(raw)["streams"].get("fhd")
except Exception:
    fhd = None
if fhd:
    t0 = time.time()
    try:
        req = urllib.request.Request(fhd, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=25) as r:
            body = r.read()
        print(f"[{r.status:>4}] {'manifest fhd (o que o player baixa)':<52} {time.time()-t0:>6.2f}s | bytes={len(body)}")
        print(f"        primeira linha: {body.splitlines()[0].decode('utf-8','replace')}")
        segs = body.count(b".ts")
        print(f"        segmentos .ts referenciados: {segs}")
    except Exception as e:
        print(f"[ ERR] manifest fhd: {type(e).__name__}: {e}")
else:
    print("[  n/a] manifest fhd: /stream nao devolveu fhd nesta janela")
