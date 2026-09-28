#!/usr/bin/env python3
"""
Mede a taxa de sucesso do endpoint /stream da API do Tomato.

O plano de functionalizacao (Tarefa 1) exige distinguir "stream morto" de
"stream oscilante". Sem esse numero, qualquer correcao no plugin e' chute.

Usa o mesmo header do plugin (lido do fonte, valor nunca impresso) e o mesmo
User-Agent do app, para que a medicao reflita o que o aparelho vera.

Rota: /v2/anime/episode/{ep_id}/stream
Por padrao usa o episodio 6412 e, se ele falhar, sorteia outros episode_ids
conhecidos, porque o /stream e' por episodio e nao por origem.

Mede em modo WARP (SOCKS5 127.0.0.1:40000) e em modo direto, para registrar se
o 500 sem proxy e' bloqueio de IP (WAF) ou indisponibilidade real.

Uso:
  python3 tools/probe_stream_rate.py                 # 20 tentativas, 3s
  python3 tools/probe_stream_rate.py --n 40 --interval 2
  python3 tools/probe_stream_rate.py --no-warp      # so modo direto
  python3 tools/probe_stream_rate.py --warp-only    # so via WARP
"""
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request

SRC = "Tomato/src/main/kotlin/com/tomato/Tomato.kt"
API = "https://prod-api.tomatoanimes.com"
WARP_PROXY = "socks5h://127.0.0.1:40000"
APP_UA = "tomato-android"
# Episodios do anime 1089, usados como fallback quando um ep_id falha.
EPISODES = [6412, 6413, 6414, 6415, 6416, 6417, 6418, 6419]


def read_token():
    """Le BEARER_TOKEN do fonte sem imprimir o valor."""
    txt = open(SRC, encoding="utf-8").read()
    key = 'const val BEARER_TOKEN = "'
    i = txt.find(key)
    if i < 0:
        return None
    i += len(key)
    j = txt.find('"', i)
    return txt[i:j] if j > i else None


def opener_for(use_warp):
    """Retorna um opener que passa pelo WARP, ou direto se use_warp for falso."""
    if not use_warp:
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        import socks  # noqa: F401  (PySocks: registra o handler socks)
    except ImportError:
        return None
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({"https": WARP_PROXY, "http": WARP_PROXY})
    )


def probe(opener, url, headers, timeout=15):
    req = urllib.request.Request(url, headers=headers)
    t0 = time.time()
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, len(r.read()), round(time.time() - t0, 2), b""
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, len(body), round(time.time() - t0, 2), body
    except Exception as e:
        return type(e).__name__, 0, round(time.time() - t0, 2), b""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--interval", type=float, default=3.0)
    ap.add_argument("--episode", type=int, default=6412)
    ap.add_argument("--warp-only", action="store_true")
    ap.add_argument("--no-warp", action="store_true")
    args = ap.parse_args()

    token = read_token()
    if not token:
        print("ERRO: BEARER_TOKEN nao encontrado em", SRC)
        return 1
    print(f"fonte: {SRC}")
    print(f"token lido: {len(token)} chars (valor NAO impresso)")
    print(f"nonce: {int(time.time())}\n")

    print("== IP de saida ==")
    for label, uw in (("direto", False), ("warp", True)):
        op = opener_for(uw)
        if op is None:
            print(f"{label:7s}: indisponivel (PySocks ausente)")
            continue
        try:
            with op.open("https://api.ipify.org", timeout=15) as r:
                print(f"{label:7s}: {r.read().decode().strip()}")
        except Exception as e:
            print(f"{label:7s}: falhou ({type(e).__name__}: {e})")
        time.sleep(1)

    modes = []
    if not args.no_warp:
        modes.append(True)
    if not args.warp_only:
        modes.append(False)

    headers = {
        "User-Agent": APP_UA,
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
    }

    for use_warp in modes:
        op = opener_for(use_warp)
        if op is None:
            print("\n== modo WARP: PySocks ausente, pulando ==")
            continue
        label = "WARP" if use_warp else "DIRETO"
        print(f"\n== /stream via {label} : {args.n} tentativas, {args.interval}s ==")
        ok = 0
        codes = {}
        first200 = False
        for i in range(args.n):
            ep = args.episode if i == 0 else random.choice(EPISODES)
            url = f"{API}/v2/anime/episode/{ep}/stream?nc={int(time.time()*1000)}"
            code, size, dt, body = probe(op, url, headers)
            codes[code] = codes.get(code, 0) + 1
            ok += 1 if code == 200 else 0
            extra = ""
            if code == 200 and not first200:
                first200 = True
                try:
                    st = json.loads(body).get("streams", {})
                    extra = f"  chaves de streams: {list(st.keys())}"
                except Exception:
                    pass
            print(f"  [{i+1:2d}/{args.n}] ep={ep} {code} {size}B {dt}s{extra}")
            time.sleep(args.interval)
        pct = 100.0 * ok / args.n
        print(f"  == taxa de sucesso: {ok}/{args.n} = {pct:.0f}%  distribuicao={codes}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
