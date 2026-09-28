#!/usr/bin/env python3
"""
Exercita a paginacao de temporada do Tomato com mais de uma pagina.

A v163 trocou o "continue" silencioso por paginacao baseada no total declarado
em SeasonEpisodesResp.episodes. A season 215 tem 22 episodios e cabe em uma
pagina, entao ela NAO exercita o caminho novo -- so prova que ele nao quebra a
temporada de uma pagina.

Este script procura uma season com total declarado maior que o tamanho de pagina
observado (25) e pagina ate o total, conferindo se o numero de ep_id unicos bate
com o total declarado. E' o teste que valida a v163 de verdade.

Rotas:
  GET  /v2/anime/{id}                      -> lista de seasons
  POST /season/{id}/episodes {page,order}   -> pagina de episodios

Le BEARER_TOKEN do fonte sem imprimir o valor. Mede via WARP por padrao.

Uso:
  python3 tools/probe_season_pagination.py
  python3 tools/probe_season_pagination.py --anime 1089 --max-pages 20
  python3 tools/probe_season_pagination.py --no-warp
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

SRC = "Tomato/src/main/kotlin/com/tomato/Tomato.kt"
API = "https://prod-api.tomatoanimes.com"
WARP_PROXY = "socks5h://127.0.0.1:40000"
APP_UA = "tomato-android"
# Tamanho de pagina observado em todas as medicoes. A API nunca documentou o
# contrato, entao isso e medida, nao garantia. Serve de heuristica para achar
# uma season que force a paginacao.
OBSERVED_PAGE_SIZE = 25


def read_token():
    txt = open(SRC, encoding="utf-8").read()
    key = 'const val BEARER_TOKEN = "'
    i = txt.find(key)
    if i < 0:
        return None
    i += len(key)
    j = txt.find('"', i)
    return txt[i:j] if j > i else None


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


def call(opener, method, url, body=None, headers=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    h = {"User-Agent": APP_UA, "Accept": "application/json"}
    if headers:
        h.update(headers)
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return type(e).__name__, b""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--anime", type=int, default=1089, help="anime inicial (o do ep 6412)")
    ap.add_argument("--candidates", type=int, default=6, help="animes a varrer")
    ap.add_argument("--max-pages", type=int, default=20)
    ap.add_argument("--no-warp", action="store_true")
    args = ap.parse_args()

    token = read_token()
    if not token:
        print("ERRO: BEARER_TOKEN nao encontrado em", SRC)
        return 1
    op = opener_for(not args.no_warp)
    if op is None:
        print("ERRO: PySocks ausente, instale com pip install PySocks")
        return 1
    # headers com o Bearer do fonte. Medido em 2026-09-27: o header e'
    # obrigatorio (403 "authentication failed" sem ele), entao toda chamada
    # autenticada precisa dele.
    H = {"User-Agent": APP_UA, "Authorization": f"Bearer {token}", "Accept": "application/json"}

    def get(path):
        c, b = call(op, "GET", API + path, headers=H)
        return c, (json.loads(b) if c == 200 and b else None)

    def post(path, body):
        c, b = call(op, "POST", API + path, body=body, headers=H)
        return c, (json.loads(b) if c == 200 and b else None)

    # 1) Animes candidatos: o informado mais alguns vizinhos do feed.
    animes = [args.anime]
    c, feed = get("/v2/animes/feed?nc=%d" % int(time.time() * 1000))
    print(f"/feed -> {c}")
    if isinstance(feed, dict):
        for item in (feed.get("data") or [])[: args.candidates]:
            a = (item or {}).get("anime_id") or (item or {}).get("id")
            if a and a not in animes:
                animes.append(a)
    print("animes candidatos:", animes[: args.candidates])

    # 2) Procura uma season com total declarado > tamanho de pagina.
    # Estrutura real medida em 2026-09-27: GET /v2/anime/{id} devolve
    # {anime_details:{...}, anime_seasons:[{season_id, season_name, season_number,
    # season_dubbed}], ...}. O total NAO vem aqui -- o /anime/{id} nao traz
    # contagem de episodios. O total so aparece em POST /season/{id}/episodes,
    # no campo `episodes`. Por isso a triagem abaixo pagina a pagina 0 de cada
    # season para ler o total, em vez de filtrar pelo detalhe.
    alvo = None
    for aid in animes[: args.candidates]:
        c, det = get(f"/v2/anime/{aid}?nc=%d" % int(time.time() * 1000))
        if c != 200 or not isinstance(det, dict):
            print(f"  anime {aid}: detalhe {c}")
            continue
        seasons = det.get("anime_seasons") or det.get("seasons") or []
        print(f"  anime {aid}: {len(seasons)} seasons")
        for s in seasons:
            if not isinstance(s, dict):
                continue
            sid = s.get("season_id") or s.get("id")
            nome = s.get("season_name") or s.get("name") or "?"
            if not sid:
                continue
            # Le o total declarado da pagina 0: e' a unica fonte do total.
            pc, r0 = post(f"/season/{sid}/episodes", {"page": 0, "order": "ASC"})
            if pc != 200 or not isinstance(r0, dict):
                print(f"    season {sid} ({nome}): pagina 0 -> HTTP {pc}")
                continue
            tot = r0.get("episodes")
            n0 = len(r0.get("data") or [])
            if isinstance(tot, int) and tot > OBSERVED_PAGE_SIZE:
                print(f"    season {sid} ({nome}): total {tot}, pagina 0 trouxe {n0} -> ALVO")
                alvo = (sid, tot, nome)
                break
            print(f"    season {sid} ({nome}): total {tot}, pagina 0 trouxe {n0}")
        if alvo:
            break

    if not alvo:
        print("\nNenhuma season com total acima de", OBSERVED_PAGE_SIZE, "-> paginacao multi-pagina NAO exercitada.")
        return 0

    sid, total, nome = alvo
    print(f"\n== paginando season {sid} ({nome}), total declarado {total} ==")
    vistos, paginas = set(), 0
    for page in range(args.max_pages):
        c, r = post(f"/season/{sid}/episodes", {"page": page, "order": "ASC"})
        if c != 200 or not isinstance(r, dict):
            print(f"  pagina {page}: HTTP {c} -> para")
            break
        eps = r.get("data") or []
        novos = 0
        for e in eps:
            k = (e or {}).get("ep_id")
            if k is not None and k not in vistos:
                vistos.add(k)
                novos += 1
        declarados = r.get("episodes")
        print(f"  pagina {page}: {len(eps)} eps, {novos} novos, total acumulado {len(vistos)} (declarado {declarados})")
        paginas += 1
        if not eps:
            print("  pagina vazia -> fim")
            break
        if len(vistos) >= (declarados if isinstance(declarados, int) else total):
            print("  total declarado atingido -> fim")
            break

    print(f"\nresultado: {len(vistos)} ep_id unicos em {paginas} paginas; total declarado {total}")
    print("BATE" if len(vistos) == total else f"DIVERGE (faltam {total - len(vistos)})")
    return 0 if len(vistos) == total else 1


if __name__ == "__main__":
    sys.exit(main())
