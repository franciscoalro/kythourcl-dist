import json, re, subprocess, time, requests, urllib3
urllib3.disable_warnings()

PREFS = "/data/data/com.tomatos.clientapp/shared_prefs/com.tomatos.clientapp_preferences.xml"
raw = subprocess.run(["adb", "-s", "localhost:5556", "shell", f"su 0 cat {PREFS}"],
                     capture_output=True, text=True, timeout=30).stdout
tok = re.search(r'name="USER_TOKEN">([^<]+)<', raw).group(1).replace("&quot;", '"').replace("&amp;", "&")
TOR = {"http": "socks5h://127.0.0.1:9050", "https": "socks5h://127.0.0.1:9050"}
H = "https://prod-api.tomatoanimes.com"
HDR = {"Accept": "application/json", "Authorization": f"Bearer {tok}"}


def call(method, path, tries=10):
    last = None
    for i in range(tries):
        try:
            last = requests.request(method, H + path, headers=HDR, timeout=30,
                                    proxies=TOR, verify=False)
            if last.status_code != 500:
                break
        except Exception as e:
            last = e
        time.sleep(min(2.0 * (i + 1), 8))
    return last


# 1. extrai ep_ids frescos do feed
feed = call("GET", "/v2/animes/feed")
eps = []
if feed.status_code == 200:
    for blk in feed.json().get("data", []):
        for it in (blk.get("data") or []):
            if it.get("ep_id"):
                eps.append((it["ep_id"], it.get("ep_anime_id"),
                            it.get("anime_name", "")[:38]))
print(f"ep_ids frescos no feed: {len(eps)}")
for e in eps[:8]:
    print("  ", e)

# 2. testa o stream em cada um
print("\n== stream por ep_id")
ok = None
for ep, aid, name in eps[:6]:
    r = call("GET", f"/v2/anime/episode/{ep}/stream", tries=6)
    if isinstance(r, Exception):
        print(f"  ep {ep} EXC {type(r).__name__}")
        continue
    if r.status_code == 200:
        st = r.json().get("streams", {})
        q = {k: (v[:46] + "..") if isinstance(v, str) else v for k, v in st.items()}
        print(f"  ep {ep} 200 OK  {name}")
        print(f"      qualidades: {q}")
        ok = (ep, st)
    else:
        print(f"  ep {ep} {r.status_code}  {name}")

if ok:
    ep, st = ok
    # 3. valida o m3u8 baixando o manifest
    m3u8 = next((v for v in st.values() if isinstance(v, str)), None)
    if m3u8:
        print("\n== baixando o manifest m3u8")
        for i in range(6):
            try:
                m = requests.get(m3u8, proxies=TOR, verify=False, timeout=30)
                if m.status_code == 200:
                    print(f"  HTTP {m.status_code} | {len(m.content)} bytes")
                    print("  primeiras linhas:")
                    for ln in m.text.splitlines()[:8]:
                        print("   ", ln[:100])
                    break
                print(f"  {m.status_code}")
            except Exception as e:
                print(f"  EXC {type(e).__name__}")
            time.sleep(2)
