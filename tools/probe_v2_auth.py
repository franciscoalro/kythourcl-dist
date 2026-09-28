#!/usr/bin/env python3
"""Sonda as rotas /v2/* do Tomato com o USER_TOKEN de sessao.

O token e lido do SharedPreferences do dispositivo em runtime e nunca
impresso: o script so reporta metodo, rota e codigo. Sem parametro = sem
credencial, o que reproduz o 403 de borda ja documentado.
"""
import json
import re
import subprocess
import sys
import time

import requests

ADB = ["adb", "-s", "localhost:5556"]
PREFS = "/data/data/com.tomatos.clientapp/shared_prefs/com.tomatos.clientapp_preferences.xml"
HOSTS = ["prod-api.tomatoanimes.com", "edge.betomato.com"]
TOR = {"http": "socks5h://127.0.0.1:9050", "https": "socks5h://127.0.0.1:9050"}

# Metodos lidos do smali (PlayerActivity.smali / SplashActivity.smali).
# 0=GET 1=POST 2=PUT 3=PATCH
ROUTES = [
    ("GET",    "/v2/anime/1"),
    ("GET",    "/v2/anime/1/episode/1"),
    ("POST",   "/v2/anime/1/episode/1"),
    ("POST",   "/v2/anime/1/episode/1/playheads"),
    ("GET",    "/v2/anime/episode/1/stream"),
    ("GET",    "/v2/playheads"),
    ("GET",    "/v2/customads/get"),
    ("GET",    "/v2/anime/list"),
    ("GET",    "/v2/anime/search?name=naruto"),
    ("GET",    "/v2/anime/1/episodes"),
]


def read_token() -> str | None:
    """Extrai o USER_TOKEN direto do device, sem ecoar o valor."""
    raw = subprocess.run(
        ADB + ["shell", f"su 0 cat {PREFS}"],
        capture_output=True, text=True, timeout=30,
    ).stdout
    m = re.search(r'name="USER_TOKEN">([^<]+)<', raw)
    if not m:
        return None
    tok = m.group(1).replace("&quot;", '"').replace("&amp;", "&")
    return tok if tok.count(".") == 2 else None


def main() -> None:
    token = read_token()
    if token is None:
        print("USER_TOKEN ausente no device -- sonda sem credencial\n")
    else:
        # valida a estrutura sem revelar o segredo
        hdr = token.split(".")[1]
        hdr += "=" * (-len(hdr) % 4)
        import base64
        claims = json.loads(base64.urlsafe_b64decode(hdr))
        exp = claims.get("exp", 0)
        print(f"USER_TOKEN presente (JWT {len(token)} bytes, "
              f"claims={sorted(claims)}, exp={exp or 'ausente'})\n")

    use_tor = "--tor" in sys.argv
    proxies = TOR if use_tor else None
    print(f"egress: {'tor' if use_tor else 'direto'}")
    print("retry: 4 por rota (o 500 de borda e intermitente)\n")

    for host in HOSTS:
        print(f"== {host}")
        for method, path in ROUTES:
            url = f"https://{host}{path}"
            headers = {"Accept": "application/json"}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            try:
                # a borda devolve 500 intermitente no mesmo egress, entao uma
                # unica amostra por rota nao distingue bloqueio de rota morta
                for attempt in range(4):
                    r = requests.request(method, url, headers=headers, timeout=25,
                                         proxies=proxies, verify=False)
                    if r.status_code != 500:
                        break
                    time.sleep(1.5 * (attempt + 1))
                body = r.text[:110].replace("\n", " ")
                note = f" | {body}"
            except Exception as e:
                r, note = None, f" | EXC {type(e).__name__}"
            # r.status_code e nao `if r`: requests.Response.__bool__ retorna
            # False para status >= 400, o que faria toda resposta de erro
            # aparecer como ERR.
            code = r.status_code if r is not None else "ERR"
            print(f"  {method:6s} {path:42s} {code}{note}")
        print()


if __name__ == "__main__":
    main()
