#!/usr/bin/env python3
"""Mapeia o contrato /v2/ de anime: feed, detalhes, temporadas e episodios.

Tudo com o USER_TOKEN de sessao lido do device. O token nunca e impresso.
Variaveis: --tor (default), --episodes (fase 2, mais lenta).
"""
import json
import re
import subprocess
import sys
import time

import requests
import urllib3
urllib3.disable_warnings()

PREFS = "/data/data/com.tomatos.clientapp/shared_prefs/com.tomatos.clientapp_preferences.xml"
HOST = "https://prod-api.tomatoanimes.com"
P = {"http": "socks5h://127.0.0.1:9050", "https": "socks5h://127.0.0.1:9050"}


def token() -> str:
    raw = subprocess.run(
        ["adb", "-s", "localhost:5556", "shell", f"su 0 cat {PREFS}"],
        capture_output=True, text=True, timeout=30).stdout
    return re.search(r'name="USER_TOKEN">([^<]+)<', raw).group(1) \
        .replace("&quot;", '"').replace("&amp;", "&")


TOK = token()


def call(method, path, body=None, tries=12):
    last = None
    for i in range(tries):
        try:
            last = requests.request(
                method, HOST + path,
                headers={"Accept": "application/json",
                         "Authorization": f"Bearer {TOK}"},
                json=body, timeout=30, proxies=P, verify=False)
            if last.status_code != 500:
                break
        except Exception as e:
            last = e
        time.sleep(min(2.0 * (i + 1), 10))
    return last


def show(title, obj, limit=8):
    print(f"\n=== {title}")
    if isinstance(obj, list):
        print(f"  lista com {len(obj)} itens")
        for it in obj[:limit]:
            if isinstance(it, dict):
                k = list(it)[:6]
                print(f"    {json.dumps(it, ensure_ascii=False)[:170]}")
            else:
                print(f"    {str(it)[:170]}")
    elif isinstance(obj, dict):
        for k, v in obj.items():
            s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
            print(f"  {k:22s} {s[:150]}")
    else:
        print(f"  {str(obj)[:200]}")


def main() -> None:
    # 1. feed
    f = call("GET", "/v2/animes/feed")
    print("FEED", f.status_code)
    j = f.json()
    print("envelope:", [k for k in j])
    show("feed.data", j.get("data", []))

    # 2. detalhes + temporadas
    d = call("GET", "/v2/anime/1539")
    j = d.json()
    seasons = j.get("anime_seasons", [])
    print("\nDETALHES", d.status_code, "| temporadas:", len(seasons))
    for s in seasons[:6]:
        print("  temporada:", json.dumps(s, ensure_ascii=False)[:230])

    # 3. rota de episodios: deriva das temporadas
    if seasons:
        s0 = seasons[0]
        sid = s0.get("season_id", s0.get("id"))
        print(f"\ntentando /v2/anime/1539/season/{sid}/episodes")
        for path in (f"/v2/anime/1539/season/{sid}/episodes",
                     f"/v2/anime/season/{sid}/episodes",
                     f"/v2/anime/1539/episodes",
                     f"/v2/season/{sid}/episodes"):
            r = call("GET", path)
            body = (r.text[:150].replace("\n", " ")
                    if not isinstance(r, Exception) else r)
            print(f"  GET  {path:44s} {r.status_code if not isinstance(r, Exception) else 'EXC'} | {body}")


if __name__ == "__main__":
    main()
