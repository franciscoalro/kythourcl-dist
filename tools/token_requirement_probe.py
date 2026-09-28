#!/usr/bin/env python3
"""Responde UMA pergunta: a API do Tomato exige o Bearer token?

Le o token do proprio plugin em disco e NUNCA o imprime. Compara a mesma
rota com e sem o header Authorization. Se as duas respostas forem iguais e
utilizaveis, o token e dispensavel e pode sair do fonte.

Nao imprime corpo de resposta inteiro: em caso de erro o corpo pode carregar
detalhe de autenticacao. Mostra apenas status, contagem de episodios e um
prefixo curto e sanitizado.
"""
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "https://prod-api.tomatoanimes.com"
SEASON = 215
UA = "tomato-android"
PLUGIN = pathlib.Path("Tomato/src/main/kotlin/com/tomato/Tomato.kt")


def load_token() -> str:
    txt = PLUGIN.read_text(encoding="utf-8", errors="ignore")
    m = re.search(r'BEARER_TOKEN\s*=\s*"([^"]+)"', txt)
    return m.group(1) if m else ""


def call(label, headers, body):
    req = urllib.request.Request(
        f"{BASE}/season/{SEASON}/episodes",
        data=json.dumps(body).encode(),
        headers=headers,
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            code, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        code, raw = e.code, e.read()
    except Exception as e:  # rede/DNS/TLS
        print(f"{label:<34} EXC    {type(e).__name__}: {str(e)[:60]}")
        return None
    ms = (time.time() - t0) * 1000

    n_ep = n_total = None
    try:
        d = json.loads(raw)
        eps = d.get("episodes") or []
        n_ep = len(eps) if isinstance(eps, list) else None
        n_total = d.get("total") if isinstance(d, dict) else None
    except Exception:
        pass

    print(f"{label:<34} {code}  {ms:6.0f}ms  episodios={n_ep}  total={n_total}")
    return code, n_ep


BASE_H = {"User-Agent": UA, "Accept": "application/json",
          "Content-Type": "application/json"}
AUTH_H = dict(BASE_H, Authorization=f"Bearer {load_token()}")
BODY = {"page": 0, "order": "ASC"}

print(f"rota: POST /season/{SEASON}/episodes  body={BODY}")
print("-" * 78)
a = call("COM Authorization", AUTH_H, BODY)
b = call("SEM Authorization", BASE_H, BODY)
print("-" * 78)

if a is None or b is None:
    print("INDETERMINADO: origem nao respondeu (caiu ou rede). Reexecutar depois.")
    sys.exit(2)

if b[0] == 200 and b[1] and b[1] == a[1]:
    print(f"VEREDITO: token DISPENSAVEL - sem Authorization devolveu {b[1]} episodios, igual ao com token.")
    print("          O token pode sair do fonte sem quebrar o plugin.")
elif b[0] in (401, 403):
    print("VEREDITO: token EXIGIDO - sem Authorization a API nega.")
else:
    print(f"VEREDITO: INCONCLUSIVO - com={a[0]}/{a[1]} sem={b[0]}/{b[1]}. Repetir em outra janela.")
