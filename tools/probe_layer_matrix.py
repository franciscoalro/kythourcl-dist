#!/usr/bin/env python3
"""
Matriz de camada para a Tarefa 0: separa "origem morta" de "IP bloqueado".

O comentario atual em isOriginDead() afirma que 500 em rota inexistente prova
origem morta de forma estrutural. Essa leitura se provou errada: num IP
bloqueado pelo WAF, o dispatch nunca roda e o 500 vem do WAF, nao do backend.

Este script mede, nas duas rotas (via WARP e direto), a mesma matriz:
  GET rota-falsa        (nao existe)
  GET rota-real         (/v2/anime/1921)
  OPTIONS rota-real     (edge/TLS/CORS, sem passar pelo backend)

A leitura correta dos tres numeros juntos:
  rota-falsa 500 + rota-real 500 + OPTIONS 2xx  -> dispatch nunca roda:
        pode ser origem morta OU IP bloqueado; OPTIONS sozinho nao separa.
  rota-falsa 4xx (404/403) + rota-real 200        -> origem VIVA.
  rota-real 403 sem token                        -> origem VIVA, so credencial.

Sonda apenas leitura. Nao imprime o token.

Uso: python3 tools/probe_layer_matrix.py
"""
import sys
import time
import urllib.error
import urllib.request

API = "https://prod-api.tomatoanimes.com"
WARP_PROXY = "socks5h://127.0.0.1:40000"
APP_UA = "tomato-android"
NONCE = str(int(time.time() * 1000))

ROUTES = [
    ("rota-falsa ", "GET", f"/zzz-nao-existe-{NONCE}"),
    ("rota-real  ", "GET", "/v2/anime/1921"),
    ("rota-real  ", "OPTIONS", "/v2/anime/1921"),
]


def opener_for(use_warp):
    if not use_warp:
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        import socks  # noqa: F401
    except ImportError:
        return None
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({"https": WARP_PROXY, "http": WARP_PROXY})
    )


def code_of(opener, method, url, timeout=15):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": APP_UA})
    try:
        with opener.open(req, timeout=timeout) as r:
            r.read()
            return r.status
    except urllib.error.HTTPError as e:
        e.read()
        return e.code
    except Exception as e:
        return type(e).__name__


def main():
    for use_warp in (True, False):
        op = opener_for(use_warp)
        label = "WARP" if use_warp else "DIRETO"
        if op is None:
            print(f"== {label}: PySocks ausente, pulando ==")
            continue
        try:
            with op.open("https://api.ipify.org", timeout=15) as r:
                ip = r.read().decode().strip()
        except Exception as e:
            ip = f"falhou ({type(e).__name__})"
        print(f"== {label} (saida {ip}) ==")
        for name, method, path in ROUTES:
            c = code_of(op, method, API + path)
            print(f"   {method:8s} {name} -> {c}")
            time.sleep(1)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
