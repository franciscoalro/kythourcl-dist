"""Rota de episodios extraida do bundle: POST /season/{season_id}/episodes {page, order}."""
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


def call(method, path, body=None, tries=10):
    r = None
    for i in range(tries):
        try:
            r = s.request(method, H + path, headers=HDRS, json=body, timeout=30)
            if r.status_code != 500:
                break
        except Exception as e:
            r = e
        time.sleep(min(1.5 * (i + 1), 6))
    return r


SID = 125  # BLEACH S1
print("== POST /season/{id}/episodes")
for label, body in (
    ("page 0 order ASC", {"page": 0, "order": "ASC"}),
    ("page 0 order DESC", {"page": 0, "order": "DESC"}),
    ("page 1 order ASC", {"page": 1, "order": "ASC"}),
    ("so page", {"page": 0}),
):
    r = call("POST", f"/season/{SID}/episodes", body)
    code = getattr(r, "status_code", r)
    if isinstance(r, Exception):
        print(f"  {label:20} EXC {type(r).__name__}")
        continue
    if code != 200:
        print(f"  {label:20} {code}  {r.text[:70]}")
        continue
    j = r.json()
    data = j.get("data")
    n = len(data) if isinstance(data, list) else "?"
    print(f"  {label:20} 200  n={n}  topo={sorted(j.keys())[:8]}")
    if isinstance(data, list) and data:
        print(f"      campos[0]={sorted(data[0].keys())[:14]}")
        print(f"      amostra={json.dumps(data[0], ensure_ascii=False)[:220]}")

print("\n== com GET, para comparacao (o dossie marcou 404)")
r = call("GET", f"/season/{SID}/episodes")
print(f"  GET  {getattr(r, 'status_code', r)}  {r.text[:70] if not isinstance(r, Exception) else ''}")

print("\n== season_ids de Bleach")
d = call("GET", "/v2/anime/1055")
if getattr(d, "status_code", None) == 200:
    for sn in d.json().get("anime_seasons", [])[:6]:
        print(f"   {sn.get('season_id'):6} {sn.get('season_name')} n={sn.get('season_number')} dub={sn.get('season_dubbed')}")
