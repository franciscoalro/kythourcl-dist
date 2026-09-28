"""Contrato de /v2/content/search extraido do bytecode Hermes.

corpo: {search, content_type, page, tags}  -- POST
"""
import re
import subprocess
import time

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
HDRS = {"Accept": "application/json", "Authorization": f"Bearer {tok}",
        "Content-Type": "application/json"}

s = requests.Session()
s.proxies = TOR
s.verify = False


def call(path, body, tries=10):
    r = None
    for i in range(tries):
        try:
            r = s.post(H + path, headers=HDRS, json=body, timeout=30)
            if r.status_code != 500:
                break
        except Exception as e:
            r = e
        time.sleep(min(1.5 * (i + 1), 6))
    return r


def show(label, r):
    code = getattr(r, "status_code", r)
    if isinstance(r, Exception):
        print(f"  {label:34} EXC {type(r).__name__}")
        return None
    try:
        j = r.json()
    except Exception:
        print(f"  {label:34} {code}  (nao-json) {r.text[:70]}")
        return None
    res = j.get("result")
    n = len(res) if isinstance(res, list) else "?"
    print(f"  {label:34} {code}  resultados={n}  sc={j.get('status_code')}")
    if isinstance(res, list) and res:
        it = res[0]
        if isinstance(it, dict):
            ks = [k for k in it.keys()][:12]
            print(f"      campos: {ks}")
            for k in ("id", "name", "type", "title", "anime_id", "manga_id"):
                if k in it:
                    print(f"      {k} = {str(it[k])[:52]}")
    return j


print("== contrato do bytecode: {search, content_type, page, tags}")
term = "bleach"
for label, body in (
    ("so search+page", {"search": term, "page": 1}),
    ("content_type=anime", {"search": term, "page": 1, "content_type": "anime"}),
    ("content_type=manga", {"search": term, "page": 1, "content_type": "manga"}),
    ("content_type=all", {"search": term, "page": 1, "content_type": "all"}),
    ("content_type vazio", {"search": term, "page": 1, "content_type": ""}),
    ("com tags vazio", {"search": term, "page": 1, "content_type": "anime", "tags": []}),
    ("com tags null", {"search": term, "page": 1, "content_type": "anime", "tags": None}),
    ("page 0", {"search": term, "page": 0, "content_type": "anime"}),
    ("page 2", {"search": term, "page": 2, "content_type": "anime"}),
):
    show(label, call("/v2/content/search", body))

print("\n== outros termos com content_type=anime")
for t in ("naruto", "one piece", "jujutsu", "a", "xyzqnaoexiste123"):
    show(f"'{t}'", call("/v2/content/search", {"search": t, "page": 1, "content_type": "anime"}))

print("\n== paginacao: page e 0-indexado")
for p in (0, 1, 2, 3, 4):
    r = call("/v2/content/search", {"search": "a", "page": p, "content_type": "anime"})
    code = getattr(r, "status_code", r)
    try:
        j = r.json()
        res = j.get("result") or []
        nomes = [str(i.get("name"))[:24] for i in res[:4]] if res else []
        print(f"  page={p}  {code}  n={len(res)}  {nomes}")
    except Exception:
        print(f"  page={p}  {code}  (nao-json)")

print("\n== content_type com termo largo")
for ct in ("anime", "manga", "", "todos", "all"):
    r = call("/v2/content/search", {"search": "a", "page": 0, "content_type": ct})
    try:
        res = r.json().get("result") or []
        tipos = sorted({str(i.get("type")) for i in res})
        print(f"  content_type={ct!r:9} n={len(res)}  tipos={tipos}")
    except Exception:
        print(f"  content_type={ct!r:9} {getattr(r, 'status_code', r)}")
