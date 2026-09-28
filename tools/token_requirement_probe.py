#!/usr/bin/env python3
"""
Prova de dispensabilidade do header Authorization na API do Tomato.

Compara a MESMA rota em duas variantes (com e sem Bearer) e diz se o header
e' exigido. Le a constante do fonte em Tomato.kt, sem imprimir o valor.

Uso: python3 tools/token_requirement_probe.py
"""
import re
import sys
import time
import urllib.error
import urllib.request

SRC = "Tomato/src/main/kotlin/com/tomato/Tomato.kt"
HOSTS = ["https://prod-api.tomatoanimes.com", "https://edge.betomato.com"]
ROUTES = ["/v2/animes/feed", "/v2/animes/2131/details"]  # rota inexistente = sonda de origem morta


def read_token():
    txt = open(SRC, encoding="utf-8").read()
    m = re.search(r'const val BEARER_TOKEN\s*=\s*"([^"]+)"', txt)
    return m.group(1) if m else None


def probe(url, headers, timeout=15):
    req = urllib.request.Request(url, headers=headers)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, len(r.read()), round(time.time() - t0, 2)
    except urllib.error.HTTPError as e:
        return e.code, len(e.read()), round(time.time() - t0, 2)
    except Exception as e:
        return type(e).__name__, 0, round(time.time() - t0, 2)


def main():
    token = read_token()
    if not token:
        print("ERRO: BEARER_TOKEN nao encontrado em", SRC)
        return 1
    print(f"fonte: {SRC}")
    print(f"token lido do fonte: {len(token)} chars (valor NAO impresso)")
    print(f"nonce: {int(time.time())}\n")

    for host in HOSTS:
        for route in ROUTES:
            url = f"{host}{route}?nc={int(time.time()*1000)}"
            base = {"User-Agent": "tomato-android", "Accept": "application/json"}
            with_auth = dict(base)
            with_auth["Authorization"] = f"Bearer {token}"
            a = probe(url, with_auth)
            b = probe(url, base)
            print(f"{host}{route}")
            print(f"   COM Authorization : {a[0]}  bytes={a[1]}  {a[2]}s")
            print(f"   SEM Authorization : {b[0]}  bytes={b[1]}  {b[2]}s")
            if a[0] == 200 and b[0] == 200:
                print("   => DISPENSAVEL: header nao muda o resultado")
            elif a[0] == 200 and b[0] in (401, 403):
                print("   => EXIGIDO: sem header a origem nega")
            elif a[0] == 500:
                print("   => ORIGEM INDISPONIVEL (500): inconclusivo")
            else:
                print("   => inconclusivo")
            print()
            time.sleep(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
