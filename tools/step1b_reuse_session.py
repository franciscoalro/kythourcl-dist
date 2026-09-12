#!/usr/bin/env python3
"""P1b: reusar storage_state do P1 e recarregar o browse canônico.

Se o cf_clearance obtido via TAB+Space for válido, o reload deve renderizar
cards SEM challenge. Só redecanais.af — sem espelhos.
"""
import os, time
STATE_DIR = "/tmp/rc-patchright"
AUTH_FILE = os.path.join(STATE_DIR, "auth.json")
TARGET = "https://redecanais.af/browse-filmes-videos-1-date.html"

from patchright.sync_api import sync_playwright
with sync_playwright() as p:
    browser = p.chromium.launch(headless=False,
        args=["--disable-blink-features=AutomationControlled", "--window-size=1366,900", "--lang=pt-BR"])
    ctx = browser.new_context(viewport={"width": 1366, "height": 900}, locale="pt-BR",
        timezone_id="America/Sao_Paulo",
        user_agent=("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"),
        storage_state=AUTH_FILE)
    page = ctx.new_page()
    mitigated = {}
    page.on("response", lambda r: mitigated.update({r.url: r.headers.get("cf-mitigated", "")}) if r.headers.get("cf-mitigated") else None)
    resp = page.goto(TARGET, wait_until="domcontentloaded", timeout=45000)
    print(f"[P1b] status={resp.status if resp else 'none'}", flush=True)
    for i in range(6):
        time.sleep(5)
        title = page.title()
        try:
            cards = page.eval_on_selector_all("#pm-grid > li", "els => els.length")
        except Exception:
            cards = -1
        names = sorted({c["name"] for c in ctx.cookies()})
        print(f"[P1b] poll#{i} title={title[:60]!r} cards={cards} cookies={names} mitigated={dict(mitigated)}", flush=True)
        if cards and cards > 0:
            html = page.content()
            open(os.path.join(STATE_DIR, "browse_solved.html"), "w").write(html)
            print(f"[P1b] SUCESSO len={len(html)}", flush=True)
            break
    ctx.storage_state(path=AUTH_FILE)
    browser.close()
print("[P1b] fim", flush=True)
