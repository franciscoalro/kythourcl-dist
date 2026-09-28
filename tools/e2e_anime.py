"""Prova ponta a ponta: busca -> detalhe -> seasons -> episodes -> stream."""
import json
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


def call(method, path, body=None, tries=20):
    """So devolve quando o status for 200 E o corpo for JSON valido."""
    ultimo = None
    for i in range(tries):
        try:
            r = s.request(method, H + path, headers=HDRS, json=body, timeout=30)
            if r.status_code == 200:
                try:
                    return r.json(), r
                except Exception:
                    ultimo = "200 sem json"
            elif r.status_code != 500:
                ultimo = f"{r.status_code}: {r.text[:80]}"
                if r.status_code in (400, 403, 404):
                    return None, r
        except Exception as e:
            ultimo = f"EXC {type(e).__name__}"
        time.sleep(min(2.0 * (i + 1), 8))
    raise SystemExit(f"falha em {method} {path} apos {tries} tentativas: {ultimo}")


print("ETAPA 1  busca por nome")
j, r = call("POST", "/v2/content/search", {"search": "bleach", "page": 0, "content_type": "anime"})
res = j.get("result") or []
alvo = next((i for i in res if str(i.get("name")) == "Bleach"), res[0])
aid = alvo["id"]
print(f"  id={aid}  {alvo['name']}  eps={alvo.get('episodes')}  {alvo.get('date')}")

print("\nETAPA 2  detalhe")
dj, d = call("GET", f"/v2/anime/{aid}")
det = dj.get("anime_details") or {}
print(f"  nome={str(det.get('name'))[:40]}  tipo={det.get('type')}  eps={det.get('episodes')}")
seasons = dj.get("anime_seasons") or []
print(f"  seasons={len(seasons)}: " + ", ".join(
    f"{x.get('season_id')}" for x in seasons[:6]))

print("\nETAPA 3  episodios da primeira season")
sid = seasons[0]["season_id"]
print(f"  season_id={sid} ({seasons[0].get('season_name')})")
ej, e = call("POST", f"/season/{sid}/episodes", {"page": 0, "order": "ASC"})
eps = ej.get("data") or []
print(f"  {len(eps)} episodios, campo 'episodes'={ej.get('episodes')}")
for x in eps[:4]:
    print(f"    ep_id={x.get('ep_id'):6} n={x.get('ep_number'):3} "
          f"{str(x.get('ep_name'))[:44]}")

print("\nETAPA 4  stream do primeiro episodio")
ep_id = eps[0]["ep_id"]
sj, st = call("GET", f"/v2/anime/episode/{ep_id}/stream")
streams = sj.get("streams", {})
for k, v in streams.items():
    print(f"    {k}: {v if v is None else str(v)[:60] + '...'}")

print("\nETAPA 5  manifesto e segmento")
m3u8 = streams.get("fhd") or streams.get("mhd")
m = s.get(m3u8, timeout=40)
seg = [l for l in m.text.splitlines() if l.strip() and not l.startswith("#")][0]
r2 = s.get(m3u8.rsplit("/", 1)[0] + "/" + seg.split("?")[0], timeout=60)
print(f"  manifest {m.status_code} | {len(m.content)} bytes")
print(f"  segmento {r2.status_code} | {len(r2.content)} bytes | "
      f"sync0x47={r2.content[:1] == bytes([0x47])}")
