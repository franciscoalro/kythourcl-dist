#!/usr/bin/env python3
"""P1: passo 1 com Patchright (Playwright patcheado anti-detect) — SEM espelhos.

Alvo canônico SOMENTE: https://redecanais.af (browse-filmes, que retorna
`Just a moment...` solucionável — a raiz `/` dá 1006/ban e NÃO é tentada).

Diferenças vs step1_unlock_session.py (Playwright puro):
- patchright.sync_api: driver patcheado (sem Runtime.enable em mundo principal,
  sem --enable-automation, closed Shadow DOM acessível).
- headless=False sob Xvfb :99 (Chrome real com viewport humano).
- storageState persistente em /tmp/rc-patchright/auth.json (session reuse —
  técnica de maior ROI segundo a comunidade).
- Detecção de challenge via header cf-mitigated (P0-1) + título.
- TAB+Espaço (tabs_till_verify do FlareSolverr) quando o clique falha.
- NUNCA usa espelhos: qualquer URL fora de redecanais.af aborta.

Uso: Xvfb :99 & python3 tools/step1_patchright.py [--click]
"""
import json, os, sys, time

STATE_DIR = "/tmp/rc-patchright"
AUTH_FILE = os.path.join(STATE_DIR, "auth.json")
TARGET = "https://redecanais.af/browse-filmes-videos-1-date.html"
ALLOWED_HOST = "redecanais.af"

os.makedirs(STATE_DIR, exist_ok=True)

from patchright.sync_api import sync_playwright

DO_CLICK = "--click" in sys.argv

with sync_playwright() as p:
    browser = p.chromium.launch(
        headless=False,  # headed sob Xvfb :99
        args=[
            "--disable-blink-features=AutomationControlled",
            "--window-size=1366,900",
            "--lang=pt-BR",
        ],
    )
    ctx_kwargs = dict(
        viewport={"width": 1366, "height": 900},
        locale="pt-BR",
        timezone_id="America/Sao_Paulo",
        user_agent=("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"),
    )
    if os.path.exists(AUTH_FILE):
        ctx_kwargs["storage_state"] = AUTH_FILE
        print(f"[P1] reusando storage_state {AUTH_FILE}", flush=True)
    ctx = browser.new_context(**ctx_kwargs)
    # storageState só é gravado no fim; garante flush mesmo em exceção
    page = ctx.new_page()

    mitigated = {}
    def on_resp(resp):
        try:
            h = resp.headers.get("cf-mitigated", "")
            if h:
                mitigated[resp.url] = h
        except Exception:
            pass
    page.on("response", on_resp)

    print(f"[P1] goto {TARGET}", flush=True)
    try:
        resp = page.goto(TARGET, wait_until="domcontentloaded", timeout=45000)
        print(f"[P1] status={resp.status if resp else 'none'}", flush=True)
    except Exception as e:
        print(f"[P1] goto falhou: {e}", flush=True)

    # guarda: nenhuma navegação para fora do canônico
    for f in page.frames:
        try:
            u = f.url
            if u.startswith("http") and ALLOWED_HOST not in u and "challenges.cloudflare.com" not in u and "cloudflare" not in u:
                print(f"[P1] ABORT espelho/externo detectado: {u}", flush=True)
                browser.close()
                sys.exit(3)
        except Exception:
            pass

    for i in range(12):
        time.sleep(5)
        try:
            title = page.title()
        except Exception as e:
            print(f"[P1] poll#{i} title falhou: {e}", flush=True)
            continue
        try:
            cards = page.eval_on_selector_all("#pm-grid > li", "els => els.length")
        except Exception:
            cards = -1
        try:
            cookies = ctx.cookies()
            names = sorted({c["name"] for c in cookies})
        except Exception:
            names = []
        chal = ("moment" in title.lower()) or any("challenge" in v for v in mitigated.values())
        print(f"[P1] poll#{i} title={title[:60]!r} cards={cards} cookies={names} mitigated={dict(mitigated)}", flush=True)

        if cards and cards > 0:
            print("[P1] SUCESSO: cards renderizados", flush=True)
            html = page.content()
            open(os.path.join(STATE_DIR, "browse_solved.html"), "w").write(html)
            print(f"[P1] html salvo len={len(html)}", flush=True)
            break

        if DO_CLICK and chal and i >= 2:
            # tenta TAB+Espaço (tabs_till_verify) — sem coordenadas, sem espelho
            try:
                page.keyboard.press("Tab")
                time.sleep(0.4)
                page.keyboard.press("Space")
                print(f"[P1] poll#{i} TAB+Space enviado", flush=True)
            except Exception as e:
                print(f"[P1] teclado falhou: {e}", flush=True)

    try:
        ctx.storage_state(path=AUTH_FILE)
        print(f"[P1] storage_state salvo em {AUTH_FILE}", flush=True)
    except Exception as e:
        print(f"[P1] storage_state falhou: {e}", flush=True)
    try:
        final_cookies = {c["name"] for c in ctx.cookies()}
        print(f"[P1] cookies finais: {sorted(final_cookies)}", flush=True)
    except Exception as e:
        print(f"[P1] cookies finais falhou: {e}", flush=True)
    browser.close()
print("[P1] fim", flush=True)
