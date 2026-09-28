"""Testa se o 400 do m3u8 e por binding de IP de saida ou por expiracao.

Gera um ep_id fresco, pega a URL assinada e tenta o manifest por
caminhos diferentes, sempre com Session persistente.
"""
import re
import subprocess
import time
from urllib.parse import urlparse

import requests
import urllib3

urllib3.disable_warnings()

PREFS = "/data/data/com.tomatos.clientapp/shared_prefs/com.tomatos.clientapp_preferences.xml"
raw = subprocess.run(["adb", "-s", "localhost:5556", "shell", f"su 0 cat {PREFS}"],
                     capture_output=True, text=True, timeout=30).stdout
tok = re.search(r'name="USER_TOKEN">([^<]+)<', raw).group(1)
tok = tok.replace("&quot;", '"').replace("&amp;", "&")

TOR = {"http": "socks5h://127.0.0.1:9050", "https": "socks5h://127.0.0.1:9050"}
H = "https://prod-api.tomatoanimes.com"
HDR = {"Accept": "application/json", "Authorization": f"Bearer {tok}"}


def api(method, path, tries=12):
    """Sessao persistente na API, com retry contra o 500 de borda."""
    s = requests.Session()
    s.proxies = TOR
    s.verify = False
    r = None
    for i in range(tries):
        try:
            r = s.request(method, H + path, headers=HDR, timeout=30)
            if r.status_code != 500:
                break
        except Exception as e:
            r = e
        time.sleep(min(1.5 * (i + 1), 6))
    return r


def head_or_get(url, proxies, label, tries=4):
    """Tenta o manifest por um caminho especifico."""
    s = requests.Session()
    s.proxies = proxies
    s.verify = False
    for i in range(tries):
        try:
            r = s.get(url, timeout=30)
            if r.status_code == 200:
                return f"  [{label}] HTTP 200 | {len(r.content)} bytes | " \
                       f"linhas={len(r.text.splitlines())}"
            last = f"  [{label}] HTTP {r.status_code}"
        except Exception as e:
            last = f"  [{label}] EXC {type(e).__name__}: {str(e)[:70]}"
        time.sleep(2 + i)
    return last


# 1. ep_id fresco
print("== buscando ep_id fresco no feed")
feed = api("GET", "/v2/animes/feed")
if getattr(feed, "status_code", None) != 200:
    print(f"  feed nao respondeu 200: {getattr(feed, 'status_code', feed)}")
    raise SystemExit(1)

eps = []
for blk in feed.json().get("data", []):
    for it in (blk.get("data") or []):
        if it.get("ep_id"):
            eps.append((it["ep_id"], it.get("ep_anime_id"), (it.get("anime_name") or "")[:40]))
print(f"  ep_ids: {len(eps)}")

# 2. procura um stream que responda 200
print("\n== stream por ep_id")
m3u8 = None
for ep, aid, name in eps[:8]:
    r = api("GET", f"/v2/anime/episode/{ep}/stream", tries=6)
    code = getattr(r, "status_code", r)
    if code == 200:
        st = r.json().get("streams", {})
        quals = {k: (v[:44] + "..") if isinstance(v, str) else v for k, v in st.items()}
        print(f"  ep {ep} 200 | {name} | {quals}")
        m3u8 = next((v for v in st.values() if isinstance(v, str)), None)
        if m3u8:
            break
    else:
        print(f"  ep {ep} {code} | {name}")

if not m3u8:
    print("\nnenhum stream 200; nada a testar")
    raise SystemExit(0)

u = urlparse(m3u8)
print(f"\n== manifest\n  host: {u.netloc}\n  path: {u.path[:70]}")

# 3. a URL carrega token/assinatura proprios?
from urllib.parse import parse_qs
q = parse_qs(u.query)
print(f"  query keys: {sorted(q.keys())}")
for k in sorted(q.keys()):
    if any(s in k.lower() for s in ("token", "sig", "key", "auth", "exp", "policy", "hash")):
        print(f"    {k}=[REDACTED]")

# 4. tres caminhos para o MESMO manifest
print("\n== tres caminhos para o mesmo manifest")
print(head_or_get(m3u8, TOR, "tor-1"))
print(head_or_get(m3u8, TOR, "tor-2"))
print(head_or_get(m3u8, {}, "direto"))

# 5. o host do CDN responde isolado?
print("\n== host do CDN isolado")
print(head_or_get(f"{u.scheme}://{u.netloc}/", TOR, "cdn-raiz", tries=2))
