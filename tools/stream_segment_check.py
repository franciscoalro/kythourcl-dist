"""Valida que o m3u8 assinado reproduz de verdade: baixa um segmento TS."""
import re
import subprocess
import time
from urllib.parse import urljoin, urlparse

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

s = requests.Session()
s.proxies = TOR
s.verify = False


def api(path, tries=12):
    r = None
    for i in range(tries):
        try:
            r = s.get(H + path, headers=HDR, timeout=30)
            if r.status_code != 500:
                break
        except Exception as e:
            r = e
        time.sleep(min(1.5 * (i + 1), 6))
    return r


# o feed precisa de 200 com JSON: exige explicitamente
feed = None
for attempt in range(20):
    r = api("/v2/animes/feed", tries=3)
    if getattr(r, "status_code", None) == 200:
        try:
            feed = r.json()
            break
        except Exception:
            feed = None
    print(f"  feed tentativa {attempt + 1}: {getattr(r, 'status_code', r)}")
    time.sleep(4)

if not feed:
    raise SystemExit("feed nao devolveu 200 com JSON apos 20 tentativas")

eps = [it["ep_id"] for blk in feed.get("data", []) for it in (blk.get("data") or []) if it.get("ep_id")]
print(f"ep_ids: {eps[:6]}")

m3u8 = None
for ep in eps[:6]:
    r = api(f"/v2/anime/episode/{ep}/stream")
    if getattr(r, "status_code", None) == 200:
        st = r.json().get("streams", {})
        m3u8 = st.get("fhd") or st.get("mhd")
        print(f"ep {ep} 200 | qualidades={list(st.keys())} | shd={st.get('shd')}")
        if m3u8:
            break
    else:
        print(f"ep {ep} {getattr(r, 'status_code', r)}")

if not m3u8:
    raise SystemExit("sem stream 200")

# manifest
m = s.get(m3u8, timeout=30)
print(f"\nmanifest {m.status_code} | {len(m.content)} bytes")
lines = [l.strip() for l in m.text.splitlines() if l.strip()]
print("cabecalho:")
for l in lines[:6]:
    print("  ", l[:100])
if "#EXT-X-KEY" in m.text:
    k = m.text.split("#EXT-X-KEY")[1].splitlines()[0]
    print(f"\nCHAVE PRESENTE: {k[:120]}")
else:
    print("\nsem EXT-X-KEY (HLS clear, sem cifragem)")

segs = [l for l in lines if not l.startswith("#")]
print(f"\nsegmentos: {len(segs)} | master? {'extm3u' in lines[0] and 'variant' in m.text}")

if "#EXTINF" not in m.text:
    print("e um master playlist; descendo a variante")
    v = s.get(urljoin(m3u8, segs[0]), timeout=30)
    print(f"  variante {v.status_code} | {len(v.content)} bytes")
    lines = [l.strip() for l in v.text.splitlines() if l.strip()]
    segs = [l for l in lines if not l.startswith("#")]

print("\n== baixando 3 segmentos reais")
for sg in segs[:3]:
    u = urljoin(m3u8, sg)
    for i in range(3):
        try:
            r = s.get(u, timeout=40)
            if r.status_code == 200:
                c = r.content
                is_ts = c[:1] == b"\x47" or c[188:189] == b"\x47"
                print(f"  {r.status_code} | {len(c)} bytes | sync0x47={is_ts} | {sg[:52]}")
                break
            last = f"  {r.status_code}"
        except Exception as e:
            last = f"  EXC {type(e).__name__}"
        time.sleep(2)
    else:
        print(last)
