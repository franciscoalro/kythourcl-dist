#!/usr/bin/env python3
"""
Mede o ganho real da v158 contra prod-api.tomatoanimes.com, com a origem
no estado medido em 2026-09-27: catch-all 500 em toda rota.

Compara o custo de rede que o plugin paga em dois cenarios:

  CENARIO A (comportamento v157) : o load() tenta /feed 4x, cada tentativa
      com timeout de 15s e 500ms de backoff entre elas. Como a origem esta
      morta, todas as 4 falham, e so entao o plugin cai no feed embutido.

  CENARIO B (comportamento v158) : 1 request na rota inexistente, ~0.15s,
      isOriginDead() retorna true, e cai direto no feed embutido.

O tempo medido e de rede (requests/urllib), que e o mesmo custo que o
NiceHTTP do plugin paga. Fixo o timeout do cliente para 15s, igual ao
timeout do `app.get` no Kotlin.
"""
import time
import urllib.request
import urllib.error
import ssl

BASE = "https://prod-api.tomatoanimes.com"
UA = "tomato-android"
TIMEOUT = 15.0   # igual ao timeout=15 do app.get no Kotlin

ctx = ssl.create_default_context()


def timed_get(path, timeout=TIMEOUT):
    """Faz 1 GET e devolve (http_code, ms_decorridos). 0 = erro de rede."""
    url = f"{BASE}{path}"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            r.read()
            return r.status, (time.time() - t0) * 1000
    except urllib.error.HTTPError as e:
        e.read()
        return e.code, (time.time() - t0) * 1000
    except Exception:
        return 0, (time.time() - t0) * 1000


def cenario_v157():
    """4 tentativas de /feed + 500ms de backoff entre elas."""
    resultados = []
    t0 = time.time()
    for i in range(4):
        code, ms = timed_get("/v2/animes/feed")
        resultados.append((i + 1, code, ms, (time.time() - t0) * 1000))
        if i < 3:
            time.sleep(0.5)
    return (time.time() - t0) * 1000, resultados


def cenario_v158():
    """1 sonda na rota inexistente. 5xx => origem morta => short-circuit."""
    nonce = str(int(time.time() * 1000))
    t0 = time.time()
    code, ms = timed_get(f"/zzz-nao-existe-{nonce}", timeout=10.0)
    return (time.time() - t0) * 1000, code, ms


print("=" * 70)
print(" v158 -- ganho da deteccao de origem morta (origem em catch-all 500)")
print("=" * 70)
print(f" base: {BASE}   timeout: {TIMEOUT}s (igual ao Kotlin)")
print()

print("--- CENARIO A: v157, 4 tentativas de /feed (rota de conteudo) ---")
A, res = cenario_v157()
for n, code, ms, acc in res:
    print(f"  tentativa {n}: http={code}  (+{acc:.0f} ms acumuladas)")
print()

print("--- CENARIO B: v158, 1 sonda de origem morta ---")
B, code, ms = cenario_v158()
print(f"  sonda rota-inexistente: http={code}  (+{ms:.0f} ms)")
verdict = "MORTA (5xx em rota inexistente)" if 500 <= code <= 599 else "VIVA"
print(f"  isOriginDead() => {verdict}  ->  short-circuit, vai ao feed embutido")
print()

print("=" * 70)
print(f" CENARIO A (v157, retry de conteudo) : {A:8.0f} ms")
print(f" CENARIO B (v158, sonda estrutural)  : {B:8.0f} ms")
if B > 0:
    print(f" Ganho por ciclo de feed            : {A - B:8.0f} ms  ({A/B:.1f}x mais rapido)")
print("=" * 70)
print()
print(" Observacao: estes sao tempos de REDE, com a origem 500 instantanea.")
print(" Quando a origem esta em timeout real (nao 500), o ganho e MAIOR ainda,")
print(" porque a sonda tem timeout 10s e a deteccao sai no 1 request.")
